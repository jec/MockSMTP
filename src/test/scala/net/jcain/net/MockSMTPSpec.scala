package net.jcain.net

import akka.actor.FSM.{CurrentState, SubscribeTransitionCallBack, Transition}
import akka.actor.{ActorSystem, Props}
import akka.io.Tcp.Connect
import akka.io.{IO, Tcp}
import akka.testkit.{ImplicitSender, TestKit, TestProbe}
import akka.util.ByteString
import java.net.InetSocketAddress
import org.scalatest.{BeforeAndAfterAll, MustMatchers, WordSpecLike}

class MockSMTPSpec extends TestKit(ActorSystem("MockSMTPSpec"))
with WordSpecLike with BeforeAndAfterAll with MustMatchers
with ImplicitSender {

  val Port = 9876

  "MockSMTP" when {
    "starting up" should {
      "bind to given port" in {
        val testProbe = TestProbe("probe-smtp")
        val server = system.actorOf(Props(classOf[MockSMTP], testProbe.ref, Port), "smtp-server")
        // wait for the server to start up and listen
        testProbe.expectMsg(MockSMTP.Ready)
        // attempt to connect to the port
        IO(Tcp) ! Connect(new InetSocketAddress("localhost", Port))
        expectMsgPF() { case Tcp.Connected(_, _) => }
      }
    }
  }

  "Handler" when {

    "createCommand()" when {
      "given invalid input" should {
        "return None" in {
          Handler.createCommand("HELO?") shouldBe None
        }
      }
    }

    "starting up" should {
      "begin the SMTP conversation" in {
        val testProbe = TestProbe("probe-handler-test")
        val stateProbe = TestProbe("probe-handler-state")
        val handler = system.actorOf(Props(classOf[Handler], testProbe.ref), "smtp-handler")
        handler ! SubscribeTransitionCallBack(stateProbe.ref)
        // wait for handler to start up
        stateProbe.expectMsg(CurrentState(handler, Handler.Greeting))
        // expect initial greeting
        val str = ByteString("220 localhost ESMTP net.jcain.net.MockSMTP")
        testProbe.expectMsgPF() { case Tcp.Write(`str`, _) => }
      }
    }

  }

}
