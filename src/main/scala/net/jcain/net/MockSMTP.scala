package net.jcain.net

import java.net.InetSocketAddress

import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import akka.io.Tcp.Bind
import akka.io.{IO, Tcp}

object MockSMTP {
  // Protocol: messages sent
  case object Ready
}

class MockSMTP(parent: ActorRef, port: Int, initialGreeting: Option[String] = None) extends Actor with ActorLogging {

  import context.system
  import MockSMTP._

  IO(Tcp) ! Bind(self, new InetSocketAddress("localhost", port))

  def receive = {

    case Tcp.Bound(_) =>
      log.info(s"Bound to port $port")
      parent ! Ready

    case Tcp.Connected(remote, local) =>
      log.info(s"Received connection")
      sender() ! Tcp.Register(context.actorOf(Props(classOf[Handler], sender(), initialGreeting), "handler"))
  }


}
