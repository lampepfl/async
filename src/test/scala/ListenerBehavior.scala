import gears.async.Async.race
import gears.async.Future
import gears.async.Future.Promise
import gears.async.Async
import gears.async.Listener
import gears.async.given
import scala.util.Success
import java.util.concurrent.atomic.AtomicBoolean
import gears.async.listeners.lockBoth
import gears.async.Listener.Locked
import gears.async.Listener.Gone
import gears.async.Listener.TopLock
import gears.async.Async.Source
import gears.async.Listener.LockMarker

class ListenerBehavior extends munit.FunSuite:
  given munit.Assertions = this

  test("race two futures"):
    val prom1 = Promise[Unit]()
    val prom2 = Promise[Unit]()
    Async.blocking:
      val raced = race(Future { prom1.future.value ; 10 }, Future { prom2.future.value ; 20 })
      assert(!raced.poll(Listener.acceptingListener(x => fail(s"race uncomplete $x"))))
      prom1.complete(Success(()))
      assertEquals(Async.await(raced).get, 10)

  test("lock two listeners"):
    val listener1 = Listener.acceptingListener[Int](x => assertEquals(x, 1))
    val listener2 = Listener.acceptingListener[Int](x => assertEquals(x, 2))
    assertEquals(lockBoth(Dummy, Dummy)(listener1, listener2), Locked)
    listener1.complete(1, Dummy)
    listener2.complete(2, Dummy)

  test("lock two listeners where one fails"):
    var listener1Locked = false
    val listener1 = new Listener[Nothing]:
      val topLock = null
      def complete(data: Nothing, src: Async.Source[Nothing]): Unit =
        fail("should not succeed")
      def release(until: Listener.LockMarker) =
        listener1Locked = false
        null
    val listener2 = NumberedTestListener(false, true, 1)

    assertEquals(lockBoth(Dummy, Dummy)(listener1, listener2), listener2)
    assert(!listener1Locked)

    assertEquals(lockBoth(Dummy, Dummy)(listener2, listener1), listener2)
    assert(!listener1Locked)

  test("lock two races"):
    val source1 = TSource()
    val source2 = TSource()

    Async.race(source1).onComplete(Listener.acceptingListener[Int](x => assertEquals(x, 1)))
    Async.race(source2).onComplete(Listener.acceptingListener[Int](x => assertEquals(x, 2)))

    assertEquals(lockBoth(source1, source2)(source1.listener.get, source2.listener.get), Locked)
    source1.completeWith(1)
    source2.completeWith(2)

  test("lock two races in reverse order"):
    val source1 = TSource()
    val source2 = TSource()

    Async.race(source1).onComplete(Listener.acceptingListener[Int](x => assertEquals(x, 1)))
    Async.race(source2).onComplete(Listener.acceptingListener[Int](x => assertEquals(x, 2)))

    assertEquals(lockBoth(source1, source2)(source2.listener.get, source1.listener.get), Locked)
    source1.completeWith(1)
    source2.completeWith(2)

  test("lock two nested races"):
    val source1 = TSource()
    val source2 = TSource()

    val race1 = Async.race(source1)
    Async.race(Async.race(source2)).onComplete(Listener.acceptingListener[Int](x => assertEquals(x, 2)))
    Async.race(race1).onComplete(Listener.acceptingListener[Int](x => assertEquals(x, 1)))

    assertEquals(lockBoth(source1, source2)(source1.listener.get, source2.listener.get), Locked)
    source1.completeWith(1)
    source2.completeWith(2)

  test("lock two nested races in reverse order"):
    val source1 = TSource()
    val source2 = TSource()

    val race1 = Async.race(source1)
    Async.race(Async.race(source2)).onComplete(Listener.acceptingListener[Int](x => assertEquals(x, 2)))
    Async.race(race1).onComplete(Listener.acceptingListener[Int](x => assertEquals(x, 1)))

    assertEquals(lockBoth(source2, source1)(source2.listener.get, source1.listener.get), Locked)
    source1.completeWith(1)
    source2.completeWith(2)

  test("race successful without wait"):
    val source1 = TSource()
    val source2 = TSource()
    val listener = TestListener(1)
    Async.race(source1, source2).onComplete(listener)

    assert(source1.listener.isDefined)
    assert(source2.listener.isDefined)

    val lock = source1.listener.get.lockCompletely(source1)
    assertEquals(lock, Locked)

    Async.blocking:
      val l2 = source2.listener.get
      val f = Future(assertEquals(l2.lockCompletely(source2), Gone))
      source1.completeWith(1)
      assert(source1.listener.isEmpty)
      assert(source2.listener.isEmpty)
      f.value

  test("race successful with wait"):
    val source1 = TSource()
    val source2 = TSource()
    val listener = NumberedTestListener(true, false, 1)
    Async.race(source1, source2).onComplete(listener)

    val l2 = source2.listener.get

    Async.blocking:
      val f1 = Future(source1.completeNowWith(1))
      listener.waitWaiter()
      listener.continue()
      val f2 = Future(l2.completeNow(1, source2))
      assert(f1.value || f2.value)
      assert(!f1.value || !f2.value)

    assert(source1.listener.isEmpty)
    assert(source2.listener.isEmpty)

  test("race failed without wait"):
    val source1 = TSource()
    val source2 = TSource()
    val listener = NumberedTestListener(false, true, 1)
    Async.race(source1, source2).onComplete(listener)

    assertEquals(source1.lockListener(), Gone)
    assert(source1.listener.isEmpty)
    assert(source2.listener.isEmpty)

  test("lockBoth of race with competing lock"):
    val source1 = TSource()
    val source2 = TSource()
    Async.race(source1, source2).onComplete(NumberedTestListener(false, false, 1))
    // listener with greatest number
    val other = new NumberedTestListener(true, false, 1)
    val s1listener = source1.listener.get

    Async.blocking:
      val f1 = Future(lockBoth(source1, Dummy)(s1listener, other))
      other.waitWaiter()
      assert(source2.listener.get.completeNow(1, source2))
      other.continue()
      assertEquals(f1.value, s1listener)

private class TestListener(expected: Int)(using asst: munit.Assertions) extends Listener[Int]:
  val topLock = null

  def complete(data: Int, source: Source[Int]): Unit =
    asst.assertEquals(data, expected)

  protected def release(to: LockMarker): Listener[?] | Null = null

private class NumberedTestListener private(sleep: AtomicBoolean, fail: Boolean, expected: Int)(using munit.Assertions) extends TestListener(expected) with Listener.NumberedLock:
  private var waiter: Option[Promise[Unit]] = None

  def this(sleep: Boolean, fail: Boolean, expected: Int)(using munit.Assertions) =
    this(AtomicBoolean(sleep), fail, expected)

  override val topLock = new TopLock:
    val selfNumber = NumberedTestListener.this.number
    def lockSelf(source: Source[?]) =
      if sleep.getAndSet(false) then
        Async.blocking:
          waiter = Some(Promise())
          waiter.get.future.value
      waiter.foreach: promise =>
        promise.complete(Success(()))
        waiter = None
      if fail then Listener.Gone
      else Listener.Locked

  def waitWaiter() =
    while waiter.isEmpty do Thread.`yield`()

  def continue() = waiter.get.complete(Success(()))

/** Dummy source that never completes */
private object Dummy extends Async.Source[Nothing]:
    def poll(k: Listener[Nothing]): Boolean = false
    def onComplete(k: Listener[Nothing]): Unit = ()
    def dropListener(k: Listener[Nothing]): Unit = ()

private class TSource(using asst: munit.Assertions) extends Async.Source[Int]:
  var listener: Option[Listener[Int]] = None
  def poll(k: Listener[Int]): Boolean = ???
  def onComplete(k: Listener[Int]): Unit =
    assert(listener.isEmpty)
    listener = Some(k)
  def dropListener(k: Listener[Int]): Unit =
    if listener.isDefined then
      asst.assertEquals(k, listener.get)
        listener = None
  def lockListener() =
    val r = listener.get.lockCompletely(this)
    if r == Listener.Gone then listener = None
    r
  def completeWith(value: Int) =
    listener.get.complete(value, this)
    listener = None
  def completeNowWith(value: Int) =
    val r = listener.get.completeNow(value, this)
    listener = None
    r
