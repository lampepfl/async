package runtime
import scala.util.boundary, boundary.Label

/** Contains a delimited continuation, which can be invoked with `resume` */
class Suspension[-T, +R]:
  def resume(arg: T): R = ???

def suspend[T, R](body: Suspension[T, R] => R)(using Label[R]): T = ???
