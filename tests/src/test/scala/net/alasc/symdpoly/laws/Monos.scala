package net.alasc.symdpoly
package laws

import shapeless.Witness
import spire.NoImplicit

import net.alasc.symdpoly.free.{MutableWord, Word}
import net.alasc.symdpoly.generic.FreeBasedMonoidDef
import net.alasc.symdpoly.{Mono, Phase, free}
import org.scalacheck.{Arbitrary, Gen}

object Monos {

  // Generators

  def genNonZeroFree[F <: free.MonoidDef.Aux[F] with Singleton:Witness.Aux]: Gen[Mono[F, F]] =
    for {
      phase <- Phase.gen
      word <- Word.gen
    } yield new Mono[F, F](new MutableWord[F](phase, word.data.length, word.data.indices, false))

  // Generator for a random free monomial
  def genFree[F <: free.MonoidDef.Aux[F] with Singleton:Witness.Aux]: Gen[Mono[F, F]] =
    Gen.frequency(1 -> Gen.const(Mono.zero[F, F]), 10 -> genNonZeroFree[F])

  def gen[M <: FreeBasedMonoidDef.Aux[F] with Singleton, F <: free.MonoidDef.Aux[F] with Singleton](implicit wM: Witness.Aux[M]): Gen[Mono[M, F]] = {
    implicit def wF: Witness.Aux[F] = (wM.value: M).witnessFree
    genFree[F].map(mono => (wM.value: M).quotient(mono))
  }

  implicit def arb[
    M <: FreeBasedMonoidDef.Aux[F] with Singleton:Witness.Aux,
    F <: free.MonoidDef.Aux[F] with Singleton:Witness.Aux
  ](implicit ev: NoImplicit[F =:= M]): Arbitrary[Mono[M, F]] = Arbitrary(gen[M, F])

  implicit def arbFree[F <: free.MonoidDef.Aux[F] with Singleton:Witness.Aux]: Arbitrary[Mono[F, F]] =
    Arbitrary(genFree[F])

}
