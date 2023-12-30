package io.jobial.bitbucket

import cats.effect.ContextShift
import cats.effect.IO
import cats.effect.Timer
import cats.implicits._
import io.circe.yaml.parser
import io.jobial.sprint.process.ProcessContext
import io.jobial.sprint.process.ProcessManagement

import scala.concurrent.duration.DurationInt
import scala.util.Try
import scala.util.matching.Regex
import scala.xml.XML

trait ModuleDependencyCalculator extends ProcessManagement[IO] {

  def updateRepoMirror(repo: String) =
    runProcessAndWait(List("git", "clone", "--mirror", bitbucketRepositoryUri(repo)), 10.minutes, reposDir).handleErrorWith(_ =>
      runProcessAndWait(List("git", "fetch", "--all"), 10.minutes, s"$reposDir/$repo.git")
    )

  def getRepoBranches(repo: String) = {
    implicit val processContext = ProcessContext(keepOutput = true)
    for {
      p <- runProcessAndWait(List("git", "for-each-ref", "--format='%(refname:short)'", "refs/heads"), 10.minutes, s"$reposDir/$repo.git")
      r <- p.getOutput
    } yield r.split("\n").map(_.trim.stripPrefix("'").stripSuffix("'")).toList
  }

  def findFilesInBranch(repo: String, branch: String, pattern: String) = {
    implicit val processContext = ProcessContext(keepOutput = true)
    for {
      p <- runProcessAndWait(List(s"/bin/sh", "-c", s"cd $reposDir/$repo.git ; git ls-tree --name-only -r $branch | grep '$pattern' || true"), 10.minutes, reposDir)
      r <- p.getOutput
    } yield r.split("\n").toList.filterNot(_.isEmpty)
  }

  def readFileFromBranch(repo: String, branch: String, file: String) = {
    implicit val processContext = ProcessContext(keepOutput = true)
    for {
      p <- runProcessAndWait(List("git", "show", s"${branch}:${file}"), 10.minutes, s"$reposDir/$repo.git")
      r <- p.getOutput
    } yield r
  }

  def readFilesFromBranch(repo: String, branch: String, filePattern: String) =
    for {
      fileNames <- findFilesInBranch(repo, branch, filePattern)
      files <- {
        for {
          fileName <- fileNames
        } yield readFileFromBranch(repo, branch, fileName)
      }.parSequence
    } yield files

  def parsePom(pom: String) = {
    val xml = XML.loadString(pom)

    val parent = (xml \ "parent" \ "artifactId").headOption.map(_.text)

    val dependencies = xml \ "dependencies"

    (parent.toSet ++ (for {
      d <- dependencies.map(_ \ "dependency").toList
      g <- d \ "groupId" if g.text === mavenGroupId
      a <- d \ "artifactId"
    } yield a.text)).map(Module(_, mainBranchName))
  }

  def updateRepoMirrors(repos: List[String]) =
    repos.map(repo => updateRepoMirror(repo).attempt).parSequence

  def readFilesFromAllBranches(repos: List[String], filePattern: String) =
    repos.map { repo =>
      for {
        branches <- getRepoBranches(repo)
        files <- branches.map { branch =>
          for {
            files <- readFilesFromBranch(repo, branch, filePattern)
          } yield Module(repo, branch) -> files
        }.parSequence
      } yield files
    }.parSequence.map(_.flatten)

  def filterOutExternalDependencies(dependencies: ModuleDependencies) =
    dependencies.view.mapValues(_.filter(dependencies.isDefinedAt)).toMap

  def parseFilesFromAllBranches(repos: List[String], parse: String => Set[Module], filePattern: String) =
    for {
      files <- readFilesFromAllBranches(repos, filePattern)
      dependencies = files.map { case (module, files) =>
        module ->
          files.flatMap { file =>
            Try(parse(file)).onError { t =>
              Try(logger.error(s"Error parsing file for $module: $file", t))
            }.getOrElse(Set())
          }.toSet
      }.toMap
    } yield filterOutExternalDependencies(dependencies)

  def getMavenDependencies(repos: List[String]) =
    parseFilesFromAllBranches(repos, parsePom, ".*pom.xml$")

  def parseBitbucketPipelines(file: String) = {
    for {
      yml <- parser.parse(file)
      image <- yml.hcursor.downField("image").downField("name").as[String]
    } yield image.substring(image.lastIndexOf('/') + 1)
  }.toOption.toSet.map(Module(_, mainBranchName))

  def getBitbucketPipelineDependencies(repos: List[String]) =
    parseFilesFromAllBranches(repos, parseBitbucketPipelines, ".*bitbucket-pipelines.yml$")

  def dockerfileFromImagePattern: Regex

  def parseDockerfileDependencies(dockerfile: String) =
    dockerfile.split("\n").flatMap { line =>
      val pattern = dockerfileFromImagePattern
      line match {
        case pattern(name) =>
          Some(name)
        case _ =>
          None
      }
    }.toSet.map(Module(_, mainBranchName))

  def getDockerDependencies(repos: List[String]) =
    parseFilesFromAllBranches(repos, parseDockerfileDependencies, ".*Dockerfile$")

  def getDependencies(repos: List[String]) =
    for {
      mavenDependencies <- getMavenDependencies(repos)
      pipelineDependencies <- getBitbucketPipelineDependencies(repos)
      dockerDependencies <- getDockerDependencies(repos)
      allDependencies = Seq(mavenDependencies, pipelineDependencies, dockerDependencies).map(_.toSeq).reduce(_ ++ _)
    } yield allDependencies.groupBy(_._1).view.mapValues { v =>
      val s = v.toSet
      s.flatMap(a => a._2)
    }.toMap

  def dependents(pipeline: Module, dependencies: ModuleDependencies) =
    dependencies.keySet.filter(dependencies(_).contains(pipeline))

  def ancestors(pipeline: Module, dependencies: ModuleDependencies): Set[Module] =
    dependencies(pipeline) ++ dependencies(pipeline).flatMap(ancestors(_, dependencies))

  def roots(pipelines: Set[Module], dependencies: ModuleDependencies) =
    pipelines.filter(p => dependencies(p).forall(!pipelines.contains(_)))

  def setBranchForAncestors(module: Module, ancestors: Set[Module], dependencies: ModuleDependencies) =
    ancestors.map { d =>
      val d1 = d.copy(branch = module.branch)
      if (dependencies.keySet.contains(d1)) d1 else d
    }

  def dependentRoots(module: Module, dependencies: ModuleDependencies) = {
    val dependenciesClosure = dependencies.map { case (k, _) => k -> setBranchForAncestors(k, ancestors(k, dependencies), dependencies) }

    roots(dependents(module, dependenciesClosure), dependenciesClosure)
  }

  val reposDir = "/tmp/repos"

  implicit protected def contextShift: ContextShift[IO]

  implicit protected def timer: Timer[IO]

  def bitbucketRepositoryUri(name: String): String

  type ModuleDependencies = Map[Module, Set[Module]]

  val emptyDependencies: ModuleDependencies = Map()
  
  def mavenGroupId: String
  
  val mainBranchName = "master"
}

case class Module(name: String, branch: String)