package io.jobial.bitbucket

import cats.effect.ContextShift
import cats.effect.IO
import cats.effect.Timer
import org.scalatest.flatspec.AsyncFlatSpec

import scala.util.matching.Regex

class ModuleDependencyCalculatorTest
  extends AsyncFlatSpec
    with ModuleDependencyCalculator {

  "dependencies" should "work" in {
    val a = DependencyModule("a", "master")
    val af = DependencyModule("a", "feature")
    val b = DependencyModule("b", "master")
    val c = DependencyModule("c", "master")
    val d = DependencyModule("d", "master")
    val df = DependencyModule("d", "feature")
    val e = DependencyModule("e", "master")
    val ef = DependencyModule("e", "feature")

    val dependencies = Map[DependencyModule, Set[DependencyModule]](
      a -> Set(),
      af -> Set(),
      b -> Set(a),
      c -> Set(a, b),
      d -> Set(a),
      df -> Set(a),
      e -> Set(b, c, d),
      ef -> Set(b, c, d)
    )

    assert(dependentRoots(a, dependencies) == Set(b, d))
    assert(dependentRoots(af, dependencies) == Set(df))
    assert(dependentRoots(df, dependencies) == Set(ef))
    assert(dependentRoots(c, dependencies) == Set(e, ef))
    assert(dependentRoots(d, dependencies) == Set(e))
    
    assert(dependenciesInBetween(a, e, dependencies) == Set(b, c, d))
    assert(dependenciesInBetween(a, c, dependencies) == Set(b))
    assert(dependenciesInBetween(a, b, dependencies) == Set())
    assert(dependenciesInBetween(a, ef, dependencies) == Set(b, c, df))
  }
  override def dockerImagePattern: Regex = ???

  override implicit protected def contextShift: ContextShift[IO] = ???

  override implicit protected def timer: Timer[IO] = ???

  override def bitbucketRepositoryUri(name: String): String = ???

  override def mavenGroupId: String = ???
}
