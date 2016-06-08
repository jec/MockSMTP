package net.jcain.net

import java.net.InetSocketAddress

import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import akka.io.Tcp.Bind
import akka.io.{IO, Tcp}

object MockSMTP {
  val rgen = new java.security.SecureRandom

  // Protocol: messages received
  final case class GetRecipients(id: String)

  // Protocol: messages sent
  case object Ready
  final case class Recipients(rcpts: List[String])
  final case class NotFound(id: String)
}

class MockSMTP(parent: ActorRef, port: Int, initialGreeting: Option[String] = None) extends Actor with ActorLogging {

  import context.system
  import MockSMTP._

  val handlers = collection.mutable.HashMap[String, ActorRef]()

  IO(Tcp) ! Bind(self, new InetSocketAddress("localhost", port))

  def receive = {

    case Tcp.Bound(_) =>
      log.info(s"Bound to port $port")
      parent ! Ready

    case Tcp.Connected(remote, local) =>
      log.info(s"Received connection")
      val id = "%016x".format(rgen.nextLong())
      val handler = context.actorOf(Props(classOf[Handler], id, sender(), initialGreeting), s"handler-$id")
      sender() ! Tcp.Register(handler)
      handlers(id) = handler

    case msg @ GetRecipients(id) => handlers.get(id) match {
      case None => sender() ! NotFound(id)
      case Some(handler) => handler forward msg
    }

  }


}
