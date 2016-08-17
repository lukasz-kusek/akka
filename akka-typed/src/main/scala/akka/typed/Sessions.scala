/**
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com/>
 */
package akka.typed

import akka.{ actor => a }
import scala.concurrent.duration._

/**
 * Big discussion point:
 *
 * What kind of relation shall there be between ActorContext and Process?
 *
 *  - If Process is agnostic of its context, then actor actions will have
 *    to be implemented by closing over the ActorContext, hence coupling
 *    the processes to the context.
 *  - If the Process shall be relocatable, it must have a way to access
 *    its context directly, without closing over an ActorContext.
 *
 * This means that in order to decouple the Process from the ActorContext
 * at runtime we must tightly couple the Process DSL to the ActorContext.
 * It is not yet clear which way to go.
 *
 * Sharing Channels between different ActorContexts must be prohibited.
 * Unfortunately we’d need @local types or similar in order to achive this
 * at compile-time, so a runtime check will have to suffice.
 */
object Sessions {

  /**
   * This will be a collection of all the management commands that are sent
   * to a Session-hosting Actor via its main channel (i.e. the ActorRef that
   * is returned directly for it).
   *
   * As an implementation detail this will include Channels, which are sent
   * as a signal that there is a message pending; this allows zero-allocation
   * messaging also for the secondary Channels.
   */
  trait Command

  /**
   * Create a Behavior that runs the given Process in a new Actor.
   */
  def toProps(p: Process[Any]): Props[Command] = ???

  /**
   * Trying out the idea of strong coupling between Sessions and ActorContext:
   * the current context is always available like this, implemented using a
   * ThreadLocal.
   */
  def actorContext: ActorContext[Command] = ???

  /**
   * Base abstraction modeling a process algebra.
   */
  trait Process[+T] { this: internal.ProcessImpl[T] =>

    /**
     * Sequences that process after this one.
     */
    def flatMap[U](f: T => Process[U]): Process[U]

    /**
     * Sequences that process after this one.
     */
    def then[U](f: T => Process[U]): Process[U] = flatMap(f)

    /**
     * Sequences that process after this one.
     *
     * This method only exists to accommodate the design choice of Scala
     * for-comprehensions to rewrite a `yield` clause to `map` and not `flatMap`.
     */
    def map[U](f: T => Process[U]): Process[U] = flatMap(f)

    /**
     * Run the given side-effects after this process completes.
     */
    def foreach(f: T => Unit): Process[Unit]

    /**
     * Evaluate the predicate for this process’ result and abort if it yields `false`.
     */
    def filter(pred: T => Boolean): Process[T]

    /**
     * Evaluate the predicate for this process’ result and abort if it yields `false`.
     */
    def withFilter(pred: T => Boolean): Process[T]

    /**
     * Filter and transform this process.
     */
    def collect[U](pf: PartialFunction[T, U]): Process[U]

    /**
     * Yields both values after both processes terminate; both processes run
     * concurrently.
     */
    def join[U](other: Process[U]): Process[(T, U)]

    /**
     * Yields the value of the first process to terminate, canceling the other;
     * both processes run concurrently.
     */
    def race[U >: T](other: Process[U]): Process[U]

    /**
     * Spawns that other process to run concurrently, without awaiting its result.
     */
    def fork(other: Process[Any]): Process[T]
  }

  sealed abstract class Termination
  case object Finished extends Termination
  case object Canceled extends Termination
  case object Failed extends Termination

  /**
   * Ensures that onTerminate runs when the given process terminates.
   * It should be noted that onTerminate can only perform synchronous side-effects.
   */
  def trap[T](onTerminate: Termination => Unit)(p: => Process[T]): Process[T] = ???

  /**
   * A Channel can be sent to (it is an ActorRef) and read from (using the `read` process).
   */
  abstract class Channel[T](_path: a.ActorPath) extends ActorRef[T](_path) { this: internal.ActorRefImpl[T] =>
    /**
     * Release all resources associated with this channel, future reads will fail.
     */
    def seal(): Unit
  }

  /**
   * Creates a fresh channel with the given buffer capacity.
   */
  def channel[T](buffer: Int): Channel[T] = ???

  /**
   * Lifts a value into a process.
   */
  def process[T](value: T): Process[T] = ???

  /**
   * Reads from a channel.
   */
  def read[T](c: Channel[T]): Process[T] = ???

  /**
   * Reads one message from the channel and then seals it.
   */
  def readAndSeal[T](c: Channel[T]): Process[T] = ???

  /**
   * Produces the given value after the given delay.
   */
  def timer[T](value: T, delay: FiniteDuration): Process[T] = ???

  /**
   * Create a process that fails after the given delay.
   */
  def timeout(delay: FiniteDuration): Process[Nothing] = ???

  /**
   * Create a process that signals termination.
   */
  def halt: Process[Nothing] = ???
}

object SessionExample {
  import Sessions._
  import patterns.Receptionist

  /*
   * Basic server protocol, demonstrating a request-response (but with more happening
   * behind the scenes)
   */
  sealed trait ServerCommand
  case object TheService extends Receptionist.ServiceKey[ServerCommand]

  case class GetIt(which: String, replyTo: ActorRef[It]) extends ServerCommand

  case class It(result: String)

  /*
   * Formulating the response is a bit more convoluted, a two-step process.
   */
  sealed trait BackendCommand
  case object BackendKey extends Receptionist.ServiceKey[BackendCommand]

  case class GetThingCode(authentication: Long, replyTo: ActorRef[Code]) extends BackendCommand

  case class Code(magicChest: ActorRef[GetTheThing])

  case class GetTheThing(which: String, replyTo: ActorRef[TheThing])

  case class TheThing(weird: String)

  /*
   * We only sketch the server processes here, the rest is assumed.
   */
  val behavior = toProps(for {
    backend <- initialize
    server <- register(backend)
  } yield run(server, backend))

  private def initialize: Process[ActorRef[BackendCommand]] = {
    val getBackend = channel[Receptionist.Listing[BackendCommand]](1)
    actorContext.system.receptionist ! Receptionist.Find(BackendKey)(getBackend)
    for (listing <- readAndSeal(getBackend)) yield {
      if (listing.addresses.isEmpty) timer((), 1.second).map(_ => initialize)
      else process(listing.addresses.head)
    }
  }

  private def register(backend: ActorRef[BackendCommand]) = {
    val registered = channel[Receptionist.Registered[ServerCommand]](1)
    val server = channel[ServerCommand](128)
    actorContext.system.receptionist ! Receptionist.Register(TheService, server)(registered)
    for {
      _ <- readAndSeal(registered) race timeout(5.seconds)
      // if this fails, the rest will not run and the actor will terminate
    } yield process(server)
  }

  private def run(server: Channel[ServerCommand], backend: ActorRef[BackendCommand]): Process[Nothing] =
    for {
      cmd <- read(server)
    } yield {
      cmd match {
        case GetIt(which, replyTo) =>
          /*
           * This is the kind of compositionality that I’m after: the activity of
           * talking with the backend can be factored out and treated completely
           * separately.
           */
          val spinOff = talkWithBackend(which, backend).foreach(thing => replyTo ! It(thing.weird))
          /*
           * Here we compose two behaviors into the same actor: accepting requests
           * and dealing with the backend.
           */
          run(server, backend) fork (spinOff race timeout(5.seconds))
      }
    }

  private def talkWithBackend(which: String, backend: ActorRef[BackendCommand]): Process[TheThing] = {
    val code = channel[Code](1)
    val thing = channel[TheThing](1)
    backend ! GetThingCode(0xdeadbeefcafeL, code)
    for {
      c <- readAndSeal(code)
    } yield {
      c.magicChest ! GetTheThing(which, thing)
      readAndSeal(thing)
    }
  }
}
