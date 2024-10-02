package io.jobial.bitbucket

import cats.Parallel
import cats.effect.Concurrent
import cats.effect.ContextShift
import cats.effect.Timer
import cats.implicits._
import io.circe.Json
import io.circe.Json.arr
import io.circe.Json.obj
import io.circe.generic.auto._
import io.circe.optics.JsonPath.root
import io.circe.parser
import io.circe.syntax.EncoderOps
import io.jobial.sprint.logging.Logging
import io.jobial.sprint.util.CatsUtils
import io.jobial.sprint.util.RateLimiter
import org.joda.time.DateTime
import org.joda.time.Duration
import org.joda.time.format.PeriodFormatterBuilder
import sttp.client3.UriContext
import sttp.client3.asString
import sttp.client3.asynchttpclient.cats.AsyncHttpClientCatsBackend
import sttp.client3.basicRequest
import sttp.model.Uri

import java.time.Instant
import java.time.Instant.now
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import scala.concurrent.duration.DurationInt
import scala.concurrent.duration.FiniteDuration

trait BitbucketClient[F[_]] extends Logging[F] with CatsUtils[F] {

  def getProjectRepos(implicit context: BitbucketContext[F], concurrent: Concurrent[F], contextShift: ContextShift[F]) =
    getPathFromBitbucketList(uri"""${context.baseUrl}/${context.workspace}?q=project.key="${context.project}"""", root.name.string)

  def getProjectRepoPipelines(maxPipelinePages: Option[Int] = Some(1))(implicit context: BitbucketContext[F], concurrent: Concurrent[F], parallel: Parallel[F], contextShift: ContextShift[F]) =
    for {
      repos <- getProjectRepos.map(_.map(BitbucketRepoPipelines(_)))
      repos <- repos.map(r => getLatestPipelines(r.name, maxPipelinePages).map(p => r.copy(latestPipelines = p))).parSequence
    } yield repos

  def getPipelinesLastTime(repository: String)(implicit context: BitbucketContext[F], concurrent: Concurrent[F], contextShift: ContextShift[F]) =
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

  def getPipelinesState(repository: String)(implicit context: BitbucketContext[F], concurrent: Concurrent[F], contextShift: ContextShift[F]) =
    for {
      r <- getPathFromBitbucketList(uri"${context.baseUrl}/${context.workspace}/$repository/pipelines?page=1&sort=-created_on", root.state.name.string, Some(1))
    } yield r

  def getLatestPipelines(repository: String, maxPages: Option[Int] = Some(1))(implicit context: BitbucketContext[F], concurrent: Concurrent[F], contextShift: ContextShift[F]) =
    for {
      r <- getPathFromBitbucketList(uri"${context.baseUrl}/${context.workspace}/$repository/pipelines?page=1&sort=-created_on", root.json, maxPages)
    } yield r.map(BitbucketPipeline(_)).sortBy(_.buildNumber).reverse

  def triggerPipelines(targets: List[(String, String)], variables: List[(String, String)] = List(), delay: FiniteDuration = 1.second)(implicit context: BitbucketContext[F], concurrent: Concurrent[F], parallel: Parallel[F], contextShift: ContextShift[F], timer: Timer[F]) =
    for {
      _ <- whenA(targets.isEmpty)(info("No pipelines to trigger"))
      results <- targets.map { case (repo, branch) =>
        info(s"Triggering pipeline $repo:$branch") >> {
          for {
            r <- triggerPipeline(repo, branch, variables)
            _ <- sleep(delay)
          } yield r
        }
      }.sequence
    } yield results

  def triggerPipeline(repository: String, branch: String, variables: List[(String, String)] = List())(implicit context: BitbucketContext[F], concurrent: Concurrent[F], contextShift: ContextShift[F]): F[TriggerPipelineResult] =
    context.rateLimiter {
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
              ),
              "variables" -> arr(
                variables.map { case (key, value) =>
                  obj(
                    "key" -> key.asJson,
                    "value" -> value.asJson
                  )
                }: _*
              )
            ).noSpaces
          )
          .auth.basic(context.user, context.password)
          .response(asString.mapLeft(new IllegalStateException(_)))

        for {
          response <- backend.send(request)
          json <- fromEither(response.body.flatMap(parser.parse))
        } yield TriggerPipelineResult(json)
      }
    }

  def getPathFromBitbucketList[T](uri: Uri, path: monocle.Optional[Json, T], maxPages: Option[Int] = None)(implicit context: BitbucketContext[F], concurrent: Concurrent[F], contextShift: ContextShift[F]) =
    for {
      pages <- getBitbucketList(uri, maxPages)
    } yield pages.flatMap(path.getOption)

  def getBitbucketList(uri: Uri, maxPages: Option[Int] = None)(implicit context: BitbucketContext[F], concurrent: Concurrent[F], contextShift: ContextShift[F]): F[List[Json]] =
    context.rateLimiter {
      if (maxPages.map(_ <= 0).getOrElse(false))
        pure(List())
      else
        AsyncHttpClientCatsBackend.resource[F]().use { backend =>
          val request = basicRequest
            .get(uri)
            .auth.basic(context.user, context.password)
            .response(asString.mapLeft(new IllegalStateException(_)))

          for {
            _ <- debug(s"Fetching list $uri")
            response <- backend.send(request)
            body <- fromEither(response.body)
            json <- fromEither(parser.parse(body))
            next = root.next.string.getOption(json)
            nextList <- next.flatMap(Uri.parse(_).toOption).map(uri => getBitbucketList(uri, maxPages.map(_ - 1))).getOrElse(pure(List()))
          } yield root.values.each.json.getAll(json) ++ nextList
        }
    }

  def getBitbucketPage(uri: Uri)(implicit context: BitbucketContext[F], concurrent: Concurrent[F], contextShift: ContextShift[F]): F[List[Json]] =
    context.rateLimiter {
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
    }

  def getRunners(implicit context: BitbucketContext[F], concurrent: Concurrent[F], parallel: Parallel[F], contextShift: ContextShift[F]) = for {
    runners <- getBitbucketList(uri"${context.internalBaseUrl}/workspaces/${context.workspaceUUID}/pipelines-config/runners")
    r <- runners.map(r => fromEither(r.as[BitbucketRunner])).parSequence
  } yield r

  def dockerBitbucketRunnerArgs(
    runnerUUID: String,
    oauthClientId: String,
    oauthClientSecret: String,
    workingDirectory: String = "/tmp"
  )(implicit context: BitbucketContext[F]) =
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

case class BitbucketContext[F[_]](
  user: String,
  password: String,
  project: String,
  workspace: String,
  workspaceUUID: String,
  rateLimiter: RateLimiter[F],
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
) {
  def isOnline = state.isOnline
}

case class BitbucketRunnerState(
  status: String,
  updated_on: String
) {
  def isOnline = status === "ONLINE"
}

case class BitbucketRepoPipelines(
  name: String,
  latestPipelines: List[BitbucketPipeline] = List()
) {

  def latestPipeline = latestPipelines.headOption
}

case class BitbucketPipeline(
  pipeline: Json
) {

  def state = root.state.name.string.getOption(pipeline)

  def refName = root.target.ref_name.string.getOption(pipeline)

  def result = root.state.result.name.string.getOption(pipeline)

  def completedTime = root.completed_on.string.getOption(pipeline).map(Instant.parse)

  def duration = root.duration_in_seconds.int.getOption(pipeline)

  def buildNumber = root.build_number.int.getOption(pipeline)

  def createdOn = root.created_on.string.getOption(pipeline).map(Instant.parse)

  def notRun = (duration === Some(0) && state === Some("COMPLETED")) || result === Some("ERROR")

  def prettyState = state match {
    case Some("COMPLETED") =>
      result match {
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

  def prettyTimestamp = formatter.format(completedTime.getOrElse(Instant.now))

  val periodFormatter = new PeriodFormatterBuilder()
    .appendMinutes().appendSuffix("m").appendSeconds().appendSuffix("s")
    .toFormatter

  def prettyDuration = duration
    .filterNot(_ === 0)
    .orElse(createdOn.map(c => (DateTime.now.getMillis - c.toEpochMilli).toInt / 1000)).map(d => new Duration(d * 1000).toPeriod())
    .map(periodFormatter.print(_)).getOrElse("")
}

case class TriggerPipelineResult(result: Json) {

  def buildNumber = root.build_number.int.getOption(result)
}