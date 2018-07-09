package net.alasc.symdpoly
package mosek

import scala.annotation.tailrec

import spire.syntax.cfor.cforRange

import scalin.immutable.{Mat, Vec}
import scalin.immutable.dense._
import scalin.syntax.all._
import scalin.Sparse

import net.alasc.symdpoly.solvers.Instance

class MosekInstance(relaxation: Relaxation[_, _, _]) extends Instance {

  import net.alasc.symdpoly.solvers.LowerTriangular.SparseMatrix
  import relaxation.{gramMatrix, objectiveVector}
  import gramMatrix.matrixSize

  require(gramMatrix.momentSet(0).isOne, "Error: empty/one monomial not part of the relaxation")
  val m = gramMatrix.nUniqueMonomials - 1 // number of constraints in the primal / variables in the dual
  val numcon = m
  val numbarvar = 1 // number of semidefinite variables in the primal / LMI in the dual
  val d = matrixSize
  val dimbarvar = Array(d) // dimension of semidefinite cone
  val lenbarvar = Array(d * (d + 1) / 2) // number of scalar semidefinite variables
  val cfix = cycloToDouble(objectiveVector(0))

  // vector b

  //val bkc = Array.fill(m)(mosek.boundkey.fx)
  val blc = Array.tabulate(m)(i => cycloToDouble(objectiveVector(i + 1)))
  val buc = blc

  // LMI constant
  val c = SparseMatrix.forMoment(gramMatrix, 0)

  // LMI matrices
  val a = Vector.tabulate(m)(i => SparseMatrix.forMoment(gramMatrix, i + 1, -1.0))

  def populateTask(task: _root_.mosek.Task): Unit = {
    /* Append 'NUMCON' empty constraints.
         The constraints will initially have no bounds. */
    task.appendcons(numcon)
    /* Append 'NUMBARVAR' semidefinite variables. */
    task.appendbarvars(dimbarvar)
    /* Optionally add a constant term to the objective. */
    task.putcfix(cfix)

    locally {
      val falpha = Array(1.0)
      val idx = Array(1L)
      task.appendsparsesymmat(dimbarvar(0), c.rows, c.cols, c.data, idx)
      task.putbarcj(0, idx, falpha)
    }

    locally {
      val bkc = Array.fill(m)(_root_.mosek.boundkey.fx)
      cforRange(0 until numcon) { i =>
        task.putconbound(i, bkc(i), blc(i), buc(i))
      }
    }

    cforRange(0 until numcon) { i =>
      val idx = Array(1L)
      val falpha = Array(1.0)
      task.appendsparsesymmat(dimbarvar(0), a(i).rows, a(i).cols, a(i).data, idx)
      task.putbaraij(i, 0, idx, falpha)
    }
  }

  def writeFile(fileName: String, tolRelGap: Double = 1e-9): Unit = {
    import resource._
    for {
      env <- managed(new _root_.mosek.Env)
      task <- managed(new _root_.mosek.Task(env))
    } {
      task.putdouparam(_root_.mosek.dparam.intpnt_co_tol_rel_gap, tolRelGap)
      task.set_Stream(_root_.mosek.streamtype.log, new _root_.mosek.Stream {
        def stream(msg: String): Unit = System.out.print(msg)
      })
      populateTask(task)
      task.writedata(fileName)
    }
  }


  def solve(tolRelGap: Double = 1e-9): Solution = {
    import resource._
    var res: Solution = null
    for {
      env <- managed(new _root_.mosek.Env)
      task <- managed(new _root_.mosek.Task(env))
    } {
      task.putdouparam(_root_.mosek.dparam.intpnt_co_tol_rel_gap, tolRelGap)
      task.set_Stream(_root_.mosek.streamtype.log, new _root_.mosek.Stream {
        def stream(msg: String): Unit = System.out.print(msg)
      })
      populateTask(task)
      task.optimize
      /* Print a summary containing information
         about the solution for debugging purposes*/
      task.solutionsummary(_root_.mosek.streamtype.msg)
      val solsta = new Array[_root_.mosek.solsta](1)
      task.getsolsta(_root_.mosek.soltype.itr, solsta)
      val Optimal = _root_.mosek.solsta.optimal
      val NearOptimal = _root_.mosek.solsta.near_optimal
      val DualInfeasCer = _root_.mosek.solsta.dual_infeas_cer
      val PrimInfeasCer = _root_.mosek.solsta.prim_infeas_cer
      val NearDualInfeasCer = _root_.mosek.solsta.near_dual_infeas_cer
      val NearPrimInfeasCer = _root_.mosek.solsta.near_prim_infeas_cer
      val Unknown = _root_.mosek.solsta.unknown

      res = solsta(0) match {
        case Optimal | NearOptimal =>
          val barx = new Array[Double](lenbarvar(0))
          task.getbarxj(_root_.mosek.soltype.itr, /* Request the interior solution. */ 0, barx)
          val y = new Array[Double](m)
          task.gety(_root_.mosek.soltype.itr, y)
          val X = MosekInstance.fromLowerTriangularColStacked(d, Vec.fromSeq(barx))
          val yvec = Vec.tabulate(m + 1) {
            case 0 => 1
            case i => y(i - 1)
          }

          @tailrec def iter(i: Int, acc: Double): Double =
            if (i == m) acc else iter(i + 1, acc + y(i) * blc(i))

          OptimumFound(None, iter(0, cfix), Some(X), yvec)
        case DualInfeasCer | PrimInfeasCer | NearDualInfeasCer | NearPrimInfeasCer =>
          Failure("Primal or dual infeasibility certificate found.")
        case Unknown =>
          Failure("The status of the solution could not be determined.")
        case _ =>
          Failure("Other solution status.")
      }
    }
    res
  }
}

object MosekInstance {

  def fromLowerTriangularColStacked[F:Sparse](d: Int, vec: Vec[F]): Mat[F] = {
    import scalin.immutable.csc._
    Mat.fromMutable(d, d, Sparse[F].zero) { mat =>
      var i = 0
      cforRange(0 until d) { c =>
        cforRange(c until d) { r =>
          mat(r, c) := vec(i)
          mat(c, r) := vec(i)
          i += 1
        }
      }
    }
  }

}
