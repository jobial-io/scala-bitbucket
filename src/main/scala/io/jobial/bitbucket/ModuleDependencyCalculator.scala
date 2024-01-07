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
    ) >> runProcessAndWait(List("git", "remote", "prune", "origin"), 10.minutes, s"$reposDir/$repo.git")

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
    } yield a.text)).map(DependencyModule(_, mainBranchName))
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
          } yield DependencyModule(repo, branch) -> files
        }.parSequence
      } yield files
    }.parSequence.map(_.flatten)

  def fixDependencyBranches(dependencies: ModuleDependencies) =
    dependencies.map { case (module, moduleDependencies) =>
      module -> moduleDependencies.map { dependency =>
        val dependencyWithBranch = dependency.copy(branch = module.branch)
        if (dependencies.isDefinedAt(dependencyWithBranch))
          dependencyWithBranch
        else
          dependency
      }
    }

  def filterOutExternalDependencies(dependencies: ModuleDependencies) =
    dependencies.mapValues(_.filter(dependencies.isDefinedAt)).toMap

  def parseFilesFromAllBranches(repos: List[String], parse: String => Set[DependencyModule], filePattern: String) =
    for {
      files <- readFilesFromAllBranches(repos, filePattern)
      dependencies = files.map { case (module, files) =>
        module ->
          files.flatMap { file =>
            Try(parse(file)).onError { case t =>
              Try(logger.error(s"Error parsing file for $module: $file", t))
            }.getOrElse(Set())
          }.toSet
      }.toMap
    } yield filterOutExternalDependencies(fixDependencyBranches(dependencies))

  def getMavenDependencies(repos: List[String]) =
    parseFilesFromAllBranches(repos, parsePom, ".*pom.xml$")

  def parseBitbucketPipelines(file: String) = {
    for {
      yml <- parser.parse(file)
      image <- yml.hcursor.downField("image").downField("name").as[String]
    } yield image.substring(image.lastIndexOf('/') + 1)
  }.toOption.toSet.map(DependencyModule(_, mainBranchName))

  def getBitbucketPipelineDependencies(repos: List[String]) =
    parseFilesFromAllBranches(repos, parseBitbucketPipelines, ".*bitbucket-pipelines.yml$")

  def dockerImagePattern: Regex

  def parseDockerfileDependencies(dockerfile: String) =
    dockerfile.split("\n").flatMap { line =>
      val pattern = dockerImagePattern
      line match {
        case pattern(name) =>
          Some(name)
        case _ =>
          None
      }
    }.toSet.map(DependencyModule(_, mainBranchName))

  def getDockerDependencies(repos: List[String]) =
    parseFilesFromAllBranches(repos, parseDockerfileDependencies, ".*Dockerfile$")

  def getDependencies(repos: List[String]) =
    for {
      mavenDependencies <- getMavenDependencies(repos)
      pipelineDependencies <- getBitbucketPipelineDependencies(repos)
      dockerDependencies <- getDockerDependencies(repos)
      allDependencies = mergeDependencies(mavenDependencies, pipelineDependencies, dockerDependencies)
    } yield allDependencies

  def mergeDependencies(dependencies: ModuleDependencies*) = {
    val allDependencies = dependencies.map(_.toList).reduce(_ ++ _)

    allDependencies.groupBy(_._1).mapValues { v =>
      val s = v.toSet
      s.flatMap(a => a._2)
    }.toMap
  }

  def dependents(pipeline: DependencyModule, dependencies: ModuleDependencies) =
    dependencies.keySet.filter(dependencies(_).contains(pipeline))

  def ancestors(pipeline: DependencyModule, dependencies: ModuleDependencies): Set[DependencyModule] =
    dependencies(pipeline) ++ dependencies(pipeline).flatMap(ancestors(_, dependencies))

  def roots(pipelines: Set[DependencyModule], dependencies: ModuleDependencies) =
    pipelines.filter(p => dependencies(p).forall(!pipelines.contains(_)))

  def setBranchForAncestors(module: DependencyModule, ancestors: Set[DependencyModule], dependencies: ModuleDependencies) =
    ancestors.map { d =>
      val d1 = d.copy(branch = module.branch)
      if (dependencies.keySet.contains(d1)) d1 else d
    }

  def dependenciesClosure(dependencies: ModuleDependencies) =
    dependencies.map { case (k, _) => k -> setBranchForAncestors(k, ancestors(k, dependencies), dependencies) }

  def dependentRoots(module: DependencyModule, dependencies: ModuleDependencies) = {
    val closure = dependenciesClosure(dependencies)

    roots(dependents(module, closure), closure)
  }

  def dependenciesInBetween(ancestor: DependencyModule, descendent: DependencyModule, dependencies: ModuleDependencies) = {
    val closure = dependenciesClosure(dependencies)

    val ancestorDependents = setBranchForAncestors(descendent, dependents(ancestor, closure), closure)
    val descendentAncestors = ancestors(descendent, closure)

    ancestorDependents.intersect(descendentAncestors)
  }

  val reposDir = "/tmp/repos"

  implicit protected def contextShift: ContextShift[IO]

  implicit protected def timer: Timer[IO]

  def bitbucketRepositoryUri(name: String): String

  type ModuleDependencies = Map[DependencyModule, Set[DependencyModule]]

  val emptyDependencies: ModuleDependencies = Map()

  def mavenGroupId: String

  val mainBranchName = "master"
}

case class DependencyModule(name: String, branch: String)
