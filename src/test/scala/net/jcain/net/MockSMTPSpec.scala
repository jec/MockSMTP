package net.jcain.net

import akka.actor.FSM.{CurrentState, SubscribeTransitionCallBack, Transition}
import akka.actor.{ActorSystem, Props, Terminated}
import akka.io.Tcp.Connect
import akka.io.{IO, Tcp}
import akka.testkit.{ImplicitSender, TestKit, TestProbe}
import akka.util.ByteString
import java.net.InetSocketAddress

import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpecLike}

import scala.concurrent.duration._

class MockSMTPSpec extends TestKit(ActorSystem("MockSMTPSpec"))
with WordSpecLike with BeforeAndAfterAll with Matchers
with ImplicitSender {

  val Port = 9876

  import Handler._

  class HandlerFixture(label: String, val initialGreeting: Option[String] = None) {

    val dataProbe = TestProbe(s"$label-probe-handler-test")
    val stateProbe = TestProbe(s"$label-probe-handler-state")
    val lifeProbe = TestProbe(s"$label-probe-handler-life")
    val handler = system.actorOf(Props(classOf[Handler], dataProbe.ref, initialGreeting), s"$label-smtp-handler")
    lifeProbe.watch(handler)
    handler ! SubscribeTransitionCallBack(stateProbe.ref)

    if (initialGreeting.isEmpty) {
      // wait for handler to start up
      stateProbe.expectMsg(CurrentState(handler, Greeting))
      // expect initial greeting
      dataProbe.expectMsgPF() { case Tcp.Write(str, _) => str shouldBe Handler.InitialGreeting }
    }

    def sendData(text: String) = handler ! Tcp.Received(ByteString(s"$text\r\n"))

    def stop() = system.stop(handler)

  }

  "MockSMTP" when {
    "starting up" should {
      "bind to given port" in {
        val testProbe = TestProbe("probe-smtp")
        val server = system.actorOf(Props(classOf[MockSMTP], testProbe.ref, Port, None), "smtp-server")
        // wait for the server to start up and listen
        testProbe.expectMsg(MockSMTP.Ready)
        // attempt to connect to the port
        IO(Tcp) ! Connect(new InetSocketAddress("localhost", Port))
        expectMsgPF() { case Tcp.Connected(_, _) => }
        system.stop(server)
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
      "given valid input" should {
        "return the correct Command" in {
          createCommand("HELO bedevere.tremtek.com") shouldBe Some(C_HELO("bedevere.tremtek.com"))
          createCommand("EHLO bedevere.tremtek.com") shouldBe Some(C_EHLO("bedevere.tremtek.com"))
          createCommand("MAIL FROM:<admin@tremtek.com>") shouldBe Some(C_MAIL("admin@tremtek.com"))
          createCommand("RCPT TO:<jcain@tremtek.com>") shouldBe Some(C_RCPT("jcain@tremtek.com"))
          createCommand("DATA") shouldBe Some(C_DATA)
          createCommand("RSET") shouldBe Some(C_RSET)
          createCommand("QUIT") shouldBe Some(C_QUIT)
        }
      }
    }

    "Greeting" when {
      "configured to respond with a busy message" should {
        "respond with the message" in new HandlerFixture("greeting-busy", initialGreeting = Some("554 Service unavailable")) {
          dataProbe.expectMsgPF() { case Tcp.Write(str, _) => str.utf8String shouldBe initialGreeting.get + "\r\n" }
          stop()
        }
      }
      "HELO" should {
        "respond to HELO and go to Idle state" in new HandlerFixture("greeting-helo") {
          // EHLO
          sendData("HELO bedevere.tremtek.com")
          dataProbe.expectMsgPF() { case Tcp.Write(str, _) => str.utf8String should startWith ("250 Hello") }
          stateProbe.expectMsg(Transition(handler, Greeting, Idle))
          stop()
        }
      }
      "EHLO" should {
        "respond to EHLO and go to Idle state" in new HandlerFixture("greeting-ehlo") {
          // EHLO
          sendData("EHLO bedevere.tremtek.com")
          dataProbe.expectMsgPF() { case Tcp.Write(str, _) => str.utf8String should startWith ("250 localhost") }
          stateProbe.expectMsg(Transition(handler, Greeting, Idle))
          stop()
        }
      }
    }

    "Idle" when {
      "MAIL FROM" should {
        "accept the sender address and go to MailFrom state" in new HandlerFixture("idle-mailfrom") {
          // EHLO
          sendData("EHLO bedevere.tremtek.com")
          dataProbe.expectMsgPF() { case Tcp.Write(str, _) => str.utf8String should startWith ("250 localhost") }
          stateProbe.expectMsg(Transition(handler, Greeting, Idle))
          // MAIL FROM
          sendData("MAIL FROM:<admin@tremtek.com>")
          dataProbe.expectMsgPF() { case Tcp.Write(str, _) => str.utf8String should startWith ("250 Ok") }
          stateProbe.expectMsg(Transition(handler, Idle, MailFrom))
          stop()
        }
      }
    }

    "MailFrom" when {
      "RCPT TO" when {
        "recipient contains NORELAY" should {
          "respond with 554 message" in new HandlerFixture("mailfrom-rcptto-norelay") {
            // EHLO
            sendData("EHLO bedevere.tremtek.com")
            dataProbe.expectMsgPF() { case Tcp.Write(str, _) => str.utf8String should startWith("250 localhost") }
            stateProbe.expectMsg(Transition(handler, Greeting, Idle))
            // MAIL FROM
            sendData("MAIL FROM:<admin@tremtek.com>")
            dataProbe.expectMsgPF() { case Tcp.Write(str, _) => str.utf8String should startWith("250 Ok") }
            stateProbe.expectMsg(Transition(handler, Idle, MailFrom))
            // RCPT TO
            sendData("RCPT TO:<jcain-NORELAY@tremtek.com>")
            dataProbe.expectMsgPF() { case Tcp.Write(str, _) => str.utf8String should startWith("554 Relay") }
            stateProbe.expectNoMsg(1.second)
            stop()
          }
        }
        "recipient contains NOTFOUND" should {
          "respond with 550 message" in new HandlerFixture("mailfrom-rcptto-notfound") {
            // EHLO
            sendData("EHLO bedevere.tremtek.com")
            dataProbe.expectMsgPF() { case Tcp.Write(str, _) => str.utf8String should startWith("250 localhost") }
            stateProbe.expectMsg(Transition(handler, Greeting, Idle))
            // MAIL FROM
            sendData("MAIL FROM:<admin@tremtek.com>")
            dataProbe.expectMsgPF() { case Tcp.Write(str, _) => str.utf8String should startWith("250 Ok") }
            stateProbe.expectMsg(Transition(handler, Idle, MailFrom))
            // RCPT TO
            sendData("RCPT TO:<jcain-NOTFOUND@tremtek.com>")
            dataProbe.expectMsgPF() { case Tcp.Write(str, _) => str.utf8String should startWith("550 User not found") }
            stateProbe.expectNoMsg(1.second)
            stop()
          }
        }
        "recipient is normal" should {
          "accept multiple recipients and go to RcptTo state and remain" in new HandlerFixture("mailfrom-rcptto-ok") {
            // EHLO
            sendData("EHLO bedevere.tremtek.com")
            dataProbe.expectMsgPF() { case Tcp.Write(str, _) => str.utf8String should startWith("250 localhost") }
            stateProbe.expectMsg(Transition(handler, Greeting, Idle))
            // MAIL FROM
            sendData("MAIL FROM:<admin@tremtek.com>")
            dataProbe.expectMsgPF() { case Tcp.Write(str, _) => str.utf8String should startWith("250 Ok") }
            stateProbe.expectMsg(Transition(handler, Idle, MailFrom))
            // RCPT TO
            sendData("RCPT TO:<jcain@tremtek.com>")
            dataProbe.expectMsgPF() { case Tcp.Write(str, _) => str.utf8String should startWith("250 Ok") }
            stateProbe.expectMsg(Transition(handler, MailFrom, RcptTo))
            sendData("RCPT TO:<chanselman@tremtek.com>")
            dataProbe.expectMsgPF() { case Tcp.Write(str, _) => str.utf8String should startWith("250 Ok") }
            stateProbe.expectNoMsg(1.second)
            sendData("RCPT TO:<jec@tremtek.com>")
            dataProbe.expectMsgPF() { case Tcp.Write(str, _) => str.utf8String should startWith("250 Ok") }
            stateProbe.expectNoMsg(1.second)
            stop()
          }
        }
      }
    }

    "RcptTo" when {
      "DATA" should {
        "go to Data state and receive text" in new HandlerFixture("rcptto-data") {
          // EHLO
          sendData("EHLO bedevere.tremtek.com")
          dataProbe.expectMsgPF() { case Tcp.Write(str, _) => str.utf8String should startWith ("250 localhost") }
          stateProbe.expectMsg(Transition(handler, Greeting, Idle))
          // MAIL FROM
          sendData("MAIL FROM:<admin@tremtek.com>")
          dataProbe.expectMsgPF() { case Tcp.Write(str, _) => str.utf8String should startWith ("250 Ok") }
          stateProbe.expectMsg(Transition(handler, Idle, MailFrom))
          // RCPT TO
          sendData("RCPT TO:<jcain@tremtek.com>")
          dataProbe.expectMsgPF() { case Tcp.Write(str, _) => str.utf8String should startWith ("250 Ok") }
          stateProbe.expectMsg(Transition(handler, MailFrom, RcptTo))
          // DATA
          sendData("DATA")
          dataProbe.expectMsgPF() { case Tcp.Write(str, _) => str.utf8String should startWith ("354 End data with") }
          stateProbe.expectMsg(Transition(handler, RcptTo, Data))
          // text
          sendData("This is a test")
          stateProbe.expectNoMsg(1.second)
          sendData("emergency broadcast")
          stateProbe.expectNoMsg(1.second)
          sendData("system.")
          stateProbe.expectNoMsg(1.second)
          sendData("This is only a test.")
          stateProbe.expectNoMsg(1.second)
          stop()
        }
      }
    }

    "Data" when {
      "receiving text without the terminator" should {
        "remain in Data state" in new HandlerFixture("data-text") {
          // EHLO
          sendData("EHLO bedevere.tremtek.com")
          dataProbe.expectMsgPF() { case Tcp.Write(str, _) => str.utf8String should startWith ("250 localhost") }
          stateProbe.expectMsg(Transition(handler, Greeting, Idle))
          // MAIL FROM
          sendData("MAIL FROM:<admin@tremtek.com>")
          dataProbe.expectMsgPF() { case Tcp.Write(str, _) => str.utf8String should startWith ("250 Ok") }
          stateProbe.expectMsg(Transition(handler, Idle, MailFrom))
          // RCPT TO
          sendData("RCPT TO:<jcain@tremtek.com>")
          dataProbe.expectMsgPF() { case Tcp.Write(str, _) => str.utf8String should startWith ("250 Ok") }
          stateProbe.expectMsg(Transition(handler, MailFrom, RcptTo))
          // DATA
          sendData("DATA")
          dataProbe.expectMsgPF() { case Tcp.Write(str, _) => str.utf8String should startWith ("354 End data with") }
          stateProbe.expectMsg(Transition(handler, RcptTo, Data))
          // text
          sendData("This is a test of")
          stateProbe.expectNoMsg(1.second)
          sendData("the emergency broadcast")
          stateProbe.expectNoMsg(1.second)
          sendData("system.")
          stateProbe.expectNoMsg(1.second)
          sendData("This is only a test.")
          stateProbe.expectNoMsg(1.second)
          stop()
        }
      }
      "receiving text followed by the terminator" should {
        "store the message and go to Idle state" in new HandlerFixture("data-term") {
          // EHLO
          sendData("EHLO bedevere.tremtek.com")
          dataProbe.expectMsgPF() { case Tcp.Write(str, _) => str.utf8String should startWith ("250 localhost") }
          stateProbe.expectMsg(Transition(handler, Greeting, Idle))
          // MAIL FROM
          sendData("MAIL FROM:<admin@tremtek.com>")
          dataProbe.expectMsgPF() { case Tcp.Write(str, _) => str.utf8String should startWith ("250 Ok") }
          stateProbe.expectMsg(Transition(handler, Idle, MailFrom))
          // RCPT TO
          sendData("RCPT TO:<jcain@tremtek.com>")
          dataProbe.expectMsgPF() { case Tcp.Write(str, _) => str.utf8String should startWith ("250 Ok") }
          stateProbe.expectMsg(Transition(handler, MailFrom, RcptTo))
          // DATA
          sendData("DATA")
          dataProbe.expectMsgPF() { case Tcp.Write(str, _) => str.utf8String should startWith ("354 End data with") }
          stateProbe.expectMsg(Transition(handler, RcptTo, Data))
          // text
          sendData("This is a test of")
          sendData("the emergency broadcast")
          sendData("system.")
          sendData("This is only a test.")
          // send remaining terminator in pieces
          List(46, 13, 10).foreach(b => handler ! Tcp.Received(ByteString(b)))
          dataProbe.expectMsgPF() { case Tcp.Write(str, _) => str.utf8String should startWith ("250 Ok: queued") }
          stateProbe.expectMsg(Transition(handler, Data, Idle))
          stop()
        }
      }
      "receiving text with _NOPTR_" should {
        "respond with a 550 and exit" in new HandlerFixture("data-noptr") {
          // EHLO
          sendData("EHLO bedevere.tremtek.com")
          dataProbe.expectMsgPF() { case Tcp.Write(str, _) => str.utf8String should startWith ("250 localhost") }
          stateProbe.expectMsg(Transition(handler, Greeting, Idle))
          // MAIL FROM
          sendData("MAIL FROM:<admin@tremtek.com>")
          dataProbe.expectMsgPF() { case Tcp.Write(str, _) => str.utf8String should startWith ("250 Ok") }
          stateProbe.expectMsg(Transition(handler, Idle, MailFrom))
          // RCPT TO
          sendData("RCPT TO:<jcain@tremtek.com>")
          dataProbe.expectMsgPF() { case Tcp.Write(str, _) => str.utf8String should startWith ("250 Ok") }
          stateProbe.expectMsg(Transition(handler, MailFrom, RcptTo))
          // DATA
          sendData("DATA")
          dataProbe.expectMsgPF() { case Tcp.Write(str, _) => str.utf8String should startWith ("354 End data with") }
          stateProbe.expectMsg(Transition(handler, RcptTo, Data))
          // text
          sendData("This is a test _NOPTR_.\r\n.")
          dataProbe.expectMsgPF() { case Tcp.Write(str, _) => str.utf8String should startWith ("550 No PTR") }
          lifeProbe.expectTerminated(handler)
          stop()
        }
      }
    }

  }

}
