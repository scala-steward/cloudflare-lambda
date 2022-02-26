package com.dwolla.lambda.cloudflare

import cats.effect.IO
import cats.effect.testing.specs2.CatsEffect
import cats.implicits._
import org.specs2.mutable.Specification
import org.typelevel.discipline.specs2.mutable.Discipline
import cats.laws.discipline.TraverseTests
import org.scalacheck._
import org.scalacheck.Arbitrary._
import org.specs2.concurrent.ExecutionEnv

import scala.concurrent.Future

class CreateOrUpdateSpec(implicit ee: ExecutionEnv) extends Specification with Discipline with CatsEffect {

  implicit def arbCreateOrUpdate[A: Arbitrary]: Arbitrary[CreateOrUpdate[A]] =
    Arbitrary(Gen.oneOf(arbitrary[A].map(Create(_)), arbitrary[A].map(Update(_))))

  checkAll("CreateOrUpdate[Int] with Option", TraverseTests[CreateOrUpdate].traverse[Int, Int, Int, Int, Option, Option])

  "CreateOrUpdate" >> {
    "should have a value" >> {
      val output: CreateOrUpdate[String] = Create("value")

      output.value must_== "value"
    }

    "Create" should {
      "return Some with the value when projected to create" >> {
        val output = Create("value").create

        output must beSome("value")
      }

      "return None when projected to Update" >> {
        val output = Create("value").update

        output must beNone
      }
    }

    "Update" should {
      "return None when projected to create" >> {
        val output = Update("value").create

        output must beNone
      }

      "return Some with the value when projected to Update" >> {
        val output = Update("value").update

        output must beSome("value")
      }
    }

    "CreateOrUpdate[_[A]]" should {
      "be sequenceable to Future[CreateOrUpdate[A]]" >> {
        val input: CreateOrUpdate[Future[String]] = Create(Future.successful("value"))

        val output: Future[CreateOrUpdate[String]] = input.sequence

        output must beLike[CreateOrUpdate[String]] {
          case x => x.value must_== "value"
        }.await
      }

      "be sequenceable to IO[CreateOrUpdate[A]]" >> {
        val input: CreateOrUpdate[IO[String]] = Create(IO.pure("value"))

        val output: IO[CreateOrUpdate[String]] = input.sequence

        output.map(_ must beLike[CreateOrUpdate[String]] {
          case x => x.value must_== "value"
        })
      }

      "be sequenceable to Option[CreateOrUpdate[A]]" >> {
        val input: CreateOrUpdate[Option[String]] = Create(Option("value"))

        val output: Option[CreateOrUpdate[String]] = input.sequence

        output must beSome[CreateOrUpdate[String]].like {
          case x => x.value must_== "value"
        }
      }
    }
  }

}
