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
    val a = Module("a", "master")
    val af = Module("a", "feature")
    val b = Module("b", "master")
    val c = Module("c", "master")
    val d = Module("d", "master")
    val df = Module("d", "feature")
    val e = Module("e", "master")
    val ef = Module("e", "feature")

    val dependencies = Map[Module, Set[Module]](
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
  }
  override def dockerfileFromImagePattern: Regex = ???

  override implicit protected def contextShift: ContextShift[IO] = ???

  override implicit protected def timer: Timer[IO] = ???

  override def bitbucketRepositoryUri(name: String): String = ???

  override def mavenGroupId: String = ???
}
