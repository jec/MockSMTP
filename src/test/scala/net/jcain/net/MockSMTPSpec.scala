package net.jcain.net

import akka.actor.FSM.{CurrentState, SubscribeTransitionCallBack, Transition}
import akka.actor.{ActorSystem, Props}
import akka.io.Tcp.Connect
import akka.io.{IO, Tcp}
import akka.testkit.{ImplicitSender, TestKit, TestProbe}
import akka.util.ByteString
import java.net.InetSocketAddress
import org.scalatest.exceptions.TestFailedException
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpecLike}
import scala.collection.mutable.ListBuffer
import scala.concurrent.duration._
import scala.language.reflectiveCalls
import scala.util.matching.Regex

class MockSMTPSpec extends TestKit(ActorSystem("MockSMTPSpec"))
with WordSpecLike with BeforeAndAfterAll with Matchers
with ImplicitSender {

  var port = 9875
  val rgen = new java.security.SecureRandom

  import Handler._

  class ServerFixture(label: String) {

    val tcpProbe = new TestProbe(system) {
      def expectResponse(regex: Regex): scala.util.matching.Regex.Match = {
        val input = new ListBuffer[String]
        while (input.isEmpty || !input.last.endsWith("\r\n"))
          expectMsgPF() { case Tcp.Received(data) => input.append(data.utf8String) }
        val response = input.mkString
        regex.findFirstMatchIn(response) match {
          case None    => throw new TestFailedException(s"$response did not match Regex $regex", 5)
          case Some(m) => m
        }
      }
    }

    port += 1
    val server = system.actorOf(Props(classOf[MockSMTP], tcpProbe.ref, port, None), s"$label-smtp-server")

    // wait for the server to start up and listen
    tcpProbe.expectMsg(MockSMTP.Ready)

    def stop() = system.stop(server)

  }

  class HandlerFixture(label: String, val initialGreeting: Option[String] = None) {

    val dataProbe = TestProbe(s"$label-probe-handler-test")
    val stateProbe = TestProbe(s"$label-probe-handler-state")
    val lifeProbe = TestProbe(s"$label-probe-handler-life")
    val handlerId = "%016x".format(rgen.nextLong())
    val handler = system.actorOf(Props(classOf[Handler], handlerId, dataProbe.ref, initialGreeting), s"$label-smtp-handler-$handlerId")
    lifeProbe.watch(handler)
    handler ! SubscribeTransitionCallBack(stateProbe.ref)

    if (initialGreeting.isEmpty) {
      // wait for handler to start up
      stateProbe.expectMsg(CurrentState(handler, Greeting))
      // expect initial greeting
      dataProbe.expectMsgPF() {
        case Tcp.Write(str, _) => str.utf8String shouldBe s"${Handler.InitialGreeting} $handlerId\r\n"
      }
    }

    def sendData(text: String) = handler ! Tcp.Received(ByteString(s"$text\r\n"))

    def stop() = system.stop(handler)

  }

  "MockSMTP" when {
    "starting up" should {
      "bind to given port" in new ServerFixture("svr-start") {
        // attempt to connect to the port
        IO(Tcp) ! Connect(new InetSocketAddress("localhost", port))
        expectMsgPF() { case Tcp.Connected(_, _) => }
        stop()
      }
    }
    "RcptTo" when {
      "GetRecipients" should {
        "reply with the recipients" in new ServerFixture("svr-rcptto-get-rcpts") {
          // connect to the port
          IO(Tcp).tell(Connect(new InetSocketAddress("localhost", port)), tcpProbe.ref)
          tcpProbe.expectMsgPF() { case Tcp.Connected(_, _) => }
          val tcp = tcpProbe.lastSender
          tcp.tell(Tcp.Register(tcpProbe.ref), tcpProbe.ref)
          val matched = tcpProbe.expectResponse("[0-9a-f]{16}$".r)
          val handlerId = matched.group(0)

          // EHLO
          tcp.tell(Tcp.Write(ByteString("EHLO bedevere.tremtek.com\r\n")), tcpProbe.ref)
          tcpProbe.expectResponse("^250 localhost".r)
          // MAIL FROM
          tcp.tell(Tcp.Write(ByteString("MAIL FROM:<admin@tremtek.com>\r\n")), tcpProbe.ref)
          tcpProbe.expectResponse("^250 Ok".r)
          // RCPT TO
          tcp.tell(Tcp.Write(ByteString("RCPT TO:<jcain@tremtek.com>\r\n")), tcpProbe.ref)
          tcpProbe.expectResponse("^250 Ok".r)
          tcp.tell(Tcp.Write(ByteString("RCPT TO:<chanselman@tremtek.com>\r\n")), tcpProbe.ref)
          tcpProbe.expectResponse("^250 Ok".r)
          tcp.tell(Tcp.Write(ByteString("RCPT TO:<jec@tremtek.com>\r\n")), tcpProbe.ref)
          tcpProbe.expectResponse("^250 Ok".r)
          // GetRecipients
          server ! MockSMTP.GetRecipients(handlerId)
          expectMsg(MockSMTP.Recipients(List("jcain@tremtek.com", "chanselman@tremtek.com", "jec@tremtek.com")))
          stop()
        }
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
          dataProbe.expectMsgPF() {
            case Tcp.Write(str, _) => str.utf8String shouldBe s"${initialGreeting.get} $handlerId\r\n"
          }
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
      "X_GET_RCPTS" should {
        "reply with the recipients" in new HandlerFixture("rcptto-get-rcpts") {
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
          // X_GET_RCPTS
          sendData("X_GET_RCPTS")
          dataProbe.expectMsgPF() { case Tcp.Write(str, _) => str.utf8String shouldBe "250 jcain@tremtek.com|chanselman@tremtek.com|jec@tremtek.com\r\n" }
          stop()
        }
      }
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
          dataProbe.expectMsgPF() { case Tcp.Write(str, _) => str.utf8String should include regex "550.*PTR".r }
          lifeProbe.expectTerminated(handler)
          stop()
        }
      }
    }

  }

}
