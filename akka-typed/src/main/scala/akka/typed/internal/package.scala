package akka.typed

package object internal {
  /*
   * These are safe due to the self-type of ActorRef
   */
  implicit class ToImpl[U](val ref: ActorRef[U]) extends AnyVal {
    def toImpl: ActorRefImpl[U] = ref.asInstanceOf[ActorRefImpl[U]]
  }
  // This one is necessary because Scala refuses to infer Nothing
  implicit class ToImplN(val ref: ActorRef[Nothing]) extends AnyVal {
    def toImplN: ActorRefImpl[Nothing] = ref.asInstanceOf[ActorRefImpl[Nothing]]
  }
}
