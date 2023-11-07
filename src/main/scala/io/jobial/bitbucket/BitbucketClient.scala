package io.jobial.bitbucket

import cats.Parallel
import cats.effect.Concurrent
import cats.effect.ContextShift
import cats.effect.IO
import cats.effect.Timer
import cats.implicits._
import io.circe.Json
import io.circe.Json.obj
import io.circe.generic.auto._
import io.circe.optics.JsonPath.root
import io.circe.parser
import io.circe.syntax.EncoderOps
import sttp.client3.UriContext
import sttp.client3.asString
import sttp.client3.asynchttpclient.cats.AsyncHttpClientCatsBackend
import sttp.client3.basicRequest
import sttp.model.Uri
import io.jobial.sprint.logging.Logging
import io.jobial.sprint.util.CatsUtils
import org.joda.time.DateTime
import org.joda.time.DateTime
import org.joda.time.Duration
import org.joda.time.format.PeriodFormatterBuilder
import sttp.client3.UriContext

import java.time.Instant
import java.time.Instant.now
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import scala.concurrent.duration.DurationInt

trait BitbucketClient[F[_]] extends Logging[F] with CatsUtils[F] {

  def getProjectRepos(implicit context: BitbucketContext, concurrent: Concurrent[F], contextShift: ContextShift[F]) =
    getPathFromBitbucketList(uri"""${context.baseUrl}/${context.workspace}?q=project.key="${context.project}"""", root.name.string)

  def getProjectRepoInfos(implicit context: BitbucketContext, concurrent: Concurrent[F], parallel: Parallel[F], contextShift: ContextShift[F]) =
    for {
      repos <- getProjectRepos.map(_.map(BitbucketRepoInfo(_)))
      repos <- repos.map(r => getLastPipeline(r.name).map(p => r.copy(lastPipeline = p))).parSequence
    } yield repos

  def getPipelinesLastTime(repository: String)(implicit context: BitbucketContext, concurrent: Concurrent[F], contextShift: ContextShift[F]) =
    for {
      r <- getBitbucketPage(uri"${context.baseUrl}/${context.workspace}/$repository/pipelines?page=1&sort=-created_on")
    } yield {
      for {
        (completedOn, state) <- r.map(p => root.completed_on.string.getOption(p).map(Instant.parse))
          .zip(r.flatMap(p => root.state.name.string.getOption(p)))
      } yield
        if (state === "IN_PROGRESS") // this is probably not needed, just checking to be on the safe side 
          now
        else
          completedOn.getOrElse(now)
    }.sortBy(_.toEpochMilli).reverse.headOption

  def getPipelinesState(repository: String)(implicit context: BitbucketContext, concurrent: Concurrent[F], contextShift: ContextShift[F]) =
    for {
      r <- getPathFromBitbucketList(uri"${context.baseUrl}/${context.workspace}/$repository/pipelines?page=1&sort=-created_on", root.state.name.string)
    } yield r

  def getLastPipeline(repository: String)(implicit context: BitbucketContext, concurrent: Concurrent[F], contextShift: ContextShift[F]) =
    for {
      r <- getPathFromBitbucketList(uri"${context.baseUrl}/${context.workspace}/$repository/pipelines?page=1&sort=-created_on", root.json)
    } yield r.headOption

  def getPipelinesNotRun(repos: List[String])(implicit context: BitbucketContext, concurrent: Concurrent[F], parallel: Parallel[F], contextShift: ContextShift[F], timer: Timer[F]) =
    for {
      lastPipelines <- repos.map(getLastPipeline(_)).parSequence.map(_.flatten)
      targets = lastPipelines.filter(root.duration_in_seconds.int.getOption(_) === Some(0)).filter(root.state.name.string.getOption(_) === Some("COMPLETED"))
        .map(json => root.repository.name.string.getOption(json).get -> root.target.ref_name.string.getOption(json).get)
    } yield targets

  def triggerPipelines(targets: List[(String, String)])(implicit context: BitbucketContext, concurrent: Concurrent[F], parallel: Parallel[F], contextShift: ContextShift[F], timer: Timer[F]) =
    for {
      _ <- whenA(targets.isEmpty)(info("No pipelines to trigger"))
      r <- targets.map { case (repo, branch) =>
        info(s"Triggering pipeline $repo:$branch") >>
          triggerPipeline(repo, branch) >> sleep(3.seconds)
      }.sequence
    } yield r

  def triggerPipeline(repository: String, branch: String)(implicit context: BitbucketContext, concurrent: Concurrent[F], contextShift: ContextShift[F]): F[_] =
    AsyncHttpClientCatsBackend.resource[F]().use { backend =>
      val request = basicRequest
        .post(uri"${context.baseUrl}/${context.workspace}/$repository/pipelines/")
        .contentType("application/json")
        .body(
          obj(
            "target" -> obj(
              "ref_type" -> "branch".asJson,
              "type" -> "pipeline_ref_target".asJson,
              "ref_name" -> branch.asJson
            )
          ).noSpaces
        )
        .auth.basic(context.user, context.password)
        .response(asString.mapLeft(new IllegalStateException(_)))

      backend.send(request)
    }

  def getPathFromBitbucketList[T](uri: Uri, path: monocle.Optional[Json, T])(implicit context: BitbucketContext, concurrent: Concurrent[F], contextShift: ContextShift[F]) =
    for {
      pages <- getBitbucketList(uri)
    } yield pages.flatMap(path.getOption)

  def getBitbucketList(uri: Uri)(implicit context: BitbucketContext, concurrent: Concurrent[F], contextShift: ContextShift[F]): F[List[Json]] =
    AsyncHttpClientCatsBackend.resource[F]().use { backend =>
      val request = basicRequest
        .get(uri)
        .auth.basic(context.user, context.password)
        .response(asString.mapLeft(new IllegalStateException(_)))

      for {
        response <- backend.send(request)
        body <- fromEither(response.body)
        json <- fromEither(parser.parse(body))
        next = root.next.string.getOption(json)
        nextList <- next.flatMap(Uri.parse(_).toOption).map(getBitbucketList).getOrElse(pure(List()))
      } yield root.values.each.json.getAll(json) ++ nextList
    }

  def getBitbucketPage(uri: Uri)(implicit context: BitbucketContext, concurrent: Concurrent[F], contextShift: ContextShift[F]): F[List[Json]] =
    AsyncHttpClientCatsBackend.resource[F]().use { backend =>
      val request = basicRequest
        .get(uri)
        .auth.basic(context.user, context.password)
        .response(asString.mapLeft(new IllegalStateException(_)))

      for {
        response <- backend.send(request)
        body <- fromEither(response.body)
        json <- fromEither(parser.parse(body))
      } yield root.values.each.json.getAll(json)
    }

  def getRunners(implicit context: BitbucketContext, concurrent: Concurrent[F], parallel: Parallel[F], contextShift: ContextShift[F]) = for {
    runners <- getBitbucketList(uri"${context.internalBaseUrl}/workspaces/${context.workspaceUUID}/pipelines-config/runners")
    r <- runners.map(r => fromEither(r.as[BitbucketRunner])).parSequence
  } yield r

  def dockerBitbucketRunnerArgs(
    runnerUUID: String,
    oauthClientId: String,
    oauthClientSecret: String,
    workingDirectory: String = "/tmp"
  )(implicit context: BitbucketContext) =
    s"""-v /tmp:/tmp -v /var/run/docker.sock:/var/run/docker.sock \\
-v /var/lib/docker/containers:/var/lib/docker/containers:ro -e ACCOUNT_UUID=${context.workspaceUUID} -e RUNNER_UUID={$runnerUUID} \\
-e RUNTIME_PREREQUISITES_ENABLED=true -e OAUTH_CLIENT_ID=$oauthClientId \\
-e OAUTH_CLIENT_SECRET=$oauthClientSecret -e WORKING_DIRECTORY=$workingDirectory \\
--name runner-$runnerUUID docker-public.packages.atlassian.com/sox/atlassian/bitbucket-pipelines-runner:1
"""

  def updateDockerCpuQuotas =
    """for i in \$(docker ps --format '{{.ID}}') ; do sudo docker update --cpu-quota -1 \$i; done
"""
}

case class BitbucketContext(
  user: String,
  password: String,
  project: String,
  workspace: String,
  workspaceUUID: String,
  baseUrl: String = "https://api.bitbucket.org/2.0/repositories",
  internalBaseUrl: String = "https://api.bitbucket.org/internal"
) {
  
  def gitUri(repo: String) =
    s"git@bitbucket.org:${workspace}/${repo}.git"
}

case class BitbucketRunner(
  uuid: String,
  name: String,
  labels: List[String],
  state: BitbucketRunnerState,
  created_on: String,
  updated_on: String
)

case class BitbucketRunnerState(
  status: String,
  updated_on: String
)


case class BitbucketRepoInfo(
  name: String,
  lastPipeline: Option[Json] = None,
  lastTime: Option[DateTime] = None
) {
  def lastPipelineState = lastPipeline.flatMap(root.state.name.string.getOption(_))

  def lastPipelineResult = lastPipeline.flatMap(root.state.result.name.string.getOption(_))

  def lastPipelineCompletedTime = lastPipeline.flatMap(root.completed_on.string.getOption(_).map(Instant.parse))

  def lastPipelineDuration = lastPipeline.flatMap(root.duration_in_seconds.int.getOption(_))

  def lastPipelineCreatedOn = lastPipeline.flatMap(root.created_on.string.getOption(_).map(Instant.parse))

  def prettyState = lastPipelineState match {
    case Some("COMPLETED") =>
      lastPipelineResult match {
        case Some(r) =>
          r.toLowerCase.capitalize
        case None =>
          ""
      }
    case Some("IN_PROGRESS") =>
      "Running"
    case Some(s) =>
      s
    case None =>
      ""
  }

  val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault)

  def prettyTimestamp = formatter.format(lastPipelineCompletedTime.getOrElse(Instant.now))

  val periodFormatter = new PeriodFormatterBuilder()
    .appendMinutes().appendSuffix("m").appendSeconds().appendSuffix("s")
    .toFormatter

  def prettyDuration = lastPipelineDuration
    .filterNot(_ === 0)
    .orElse(lastPipelineCreatedOn.map(c => (DateTime.now.getMillis - c.toEpochMilli).toInt / 1000)).map(d => new Duration(d * 1000).toPeriod())
    .map(periodFormatter.print(_)).getOrElse("")
}
