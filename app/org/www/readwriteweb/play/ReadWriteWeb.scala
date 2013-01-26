package org.www.readwriteweb.play

import play.api.mvc.Action
import org.w3.banana._
import plantain._
import plantain.ParentDoesNotExist
import plantain.ResourceExists
import play.api.mvc.Results._
import concurrent.{Future, ExecutionContext}
import java.io.{StringWriter, PrintWriter}
import play.api.mvc.SimpleResult
import play.api.mvc.ResponseHeader
import java.net.URLDecoder
import play.api.libs.Files.TemporaryFile
import org.www.readwriteweb.play.QueryRwwContent
import org.www.readwriteweb.play.GraphRwwContent
import scala.Some
import play.api.mvc.SimpleResult
import play.api.mvc.ResponseHeader
import org.www.readwriteweb.play.BinaryRwwContent

/**
 * ReadWriteWeb Controller for Play
 */
trait ReadWriteWeb[Rdf <: RDF]{

  def rwwActor: ResourceMgr[Rdf]
  implicit def rwwBodyParser: RwwBodyParser[Rdf]
  implicit def ec: ExecutionContext

  implicit def graphWriterSelector: WriterSelector[Rdf#Graph]
  implicit def solutionsWriterSelector: WriterSelector[Rdf#Solutions]
  implicit val boolWriterSelector: WriterSelector[Boolean] = BooleanWriter.selector

  import org.www.readwriteweb.play.PlayWriterBuilder._

  def about = Action {
    Ok( views.html.rww.ldp() )
  }

  def stackTrace(e: Throwable) = {
    val sw = new StringWriter(1024)
    e.printStackTrace(new PrintWriter(sw))
    sw.getBuffer.toString
  }

  //    JenaRDFBlockingWriter.WriterSelector()
  //    req.accept.collectFirst {
  //      case "application/rdf+xml" =>  (writeable(JenaRdfXmlWriter),ContentTypeOf[Jena#Graph](Some("application/rdf+xml")))
  //      case "text/turtle" => (writeable(JenaTurtleWriter), ContentTypeOf[Jena#Graph](Some("text/turtle")))
  //      case m @ SparqlAnswerJson.mime => (writeable(JenaSparqlJSONWriter), ContentTypeOf[JenaSPARQL#Solutions](Some(m)))
  //    }.get

  def get(path: String) = Action { request =>
    System.out.println("in GET on resource <" + path + ">")

    Async {
      val res = for {
        namedRes <- rwwActor.get(path)
      } yield {
        namedRes match {
          case ldpr: LDPR[Rdf] =>
            writerFor[Rdf#Graph](request).map {
              wr => result(200, wr)(ldpr.graph)
            } getOrElse {
              UnsupportedMediaType("could not find serialiser for Accept types " +
                request.headers.get(play.api.http.HeaderNames.ACCEPT))
            }
          case bin: BinaryResource[Rdf] => {
            SimpleResult(
              header = ResponseHeader(200, Map("Content-Type" -> "todo")),
              body = bin.reader(1024 * 8)
            )
          }
        }
      }
      res recover {
        case nse: NoSuchElementException => NotFound(nse.getMessage+stackTrace(nse))
        case e => ExpectationFailed(e.getMessage+"\n"+stackTrace(e))
      }
    }
  }

  /**
   * http://tools.ietf.org/html/rfc4918#section-9.3
   * @param path
   * @return
   */
  def mkcol(path: String) = Action(rwwBodyParser) { request =>
    val correctedPath = if (!path.endsWith("/")) path else path+"/"
    Async {
      def mk(graph: Option[Rdf#Graph]): Future[SimpleResult[String]] = {
        for {
          answer <- rwwActor.makeCollection(correctedPath, graph)
        } yield {
          val res = Created("Created Collection at " + answer)
          if (path == correctedPath) res
          else res.withHeaders(("Location" -> answer.toString))
        }
      }
      val res = request.body match {
        case rww: GraphRwwContent[Rdf]  => mk(Some(rww.graph))
        case emptyContent => mk(None)
        case _ => Future.successful(UnsupportedMediaType("We only support RDF media types, for appending to collection."))
      }

      res recover {
        case ResourceExists(e) => MethodNotAllowed(e)
        case ParentDoesNotExist(e) => Conflict(e)
        case AccessDenied(e) => Forbidden(e)
        case e => InternalServerError(e.toString+"\n"+stackTrace(e))
      }
    }
  }

  def put(path: String) = Action(rwwBodyParser) { request =>
    Async {
      val future = for {
        answer <- rwwActor.put(path, request.body)
      } yield {
        Ok("Succeeded")
      }
      future recover {
        case nse: NoSuchElementException => NotFound(nse.getMessage+stackTrace(nse))
        case e => ExpectationFailed(e.getMessage +"\n"+stackTrace(e))
      }
    }
  }


  def post(path: String) = Action(rwwBodyParser) { request =>
    Async {
      System.out.println(s"post($path)")
      val future = request.body match {
        case rwwGraph: GraphRwwContent[Rdf] => {
           for {
            location <- rwwActor.postGraph(path, rwwGraph,
              request.headers.get("Slug").map( t => URLDecoder.decode(t,"UTF-8"))
            )
          } yield {
            Created.withHeaders("Location" -> location.toString)
          }
        }
        case rwwQuery: QueryRwwContent[Rdf] => {
          for {
            answer <- rwwActor.postQuery(path,rwwQuery)
          } yield {
             answer.fold(
               graph => writerFor[Rdf#Graph](request).map {
                 wr => result(200, wr)(graph)
               },
               sol => writerFor[Rdf#Solutions](request).map {
                  wr => result(200, wr)(sol)
                },
                bool => writerFor[Boolean](request).map {
                  wr => result(200, wr)(bool)
                }
              ).getOrElse(UnsupportedMediaType(s"Cannot publish anser of type ${answer.getClass} as"+
                s"one of the mime types given ${request.headers.get("Accept")}"))
          }
        }
        case BinaryRwwContent(file: TemporaryFile, mime: String) => {
          for {
            location <- rwwActor.postBinary(path,
                request.headers.get("Slug").map( t => URLDecoder.decode(t,"UTF-8")),
                file,
                MimeType(mime))
          } yield {
            Created.withHeaders("Location" -> location.toString)
          }
        }
//        case _ => Ok("received content")
      }
      future recover {
        case nse: NoSuchElementException => NotFound(nse.getMessage+stackTrace(nse))
        case e => ExpectationFailed(e.getMessage+"\n"+stackTrace(e))
      }
    }
  }

  def delete(path: String) = Action { request =>
    Async {
      System.out.println(s"post($path)")
      val future = for {
        _ <- rwwActor.delete(path)
      } yield {
        Ok
      }
      future recover {
        case nse: NoSuchElementException => NotFound(nse.getMessage+stackTrace(nse))
        case e => ExpectationFailed(e.getMessage+"\n"+stackTrace(e))
      }
    }
  }
}






