/*
 * Copyright (c) 2009 Matthew Hildebrand <matt.hildebrand@gmail.com>
 * Copyright (C) 2009, Progress Software Corporation and/or its
 * subsidiaries or affiliates.  All rights reserved.
 *
 * Permission to use, copy, modify, and/or distribute this software for any
 * purpose with or without fee is hereby granted, provided that the above
 * copyright notice and this permission notice appear in all copies.
 *
 * THE SOFTWARE IS PROVIDED "AS IS" AND THE AUTHOR DISCLAIMS ALL WARRANTIES
 * WITH REGARD TO THIS SOFTWARE INCLUDING ALL IMPLIED WARRANTIES OF
 * MERCHANTABILITY AND FITNESS. IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR
 * ANY SPECIAL, DIRECT, INDIRECT, OR CONSEQUENTIAL DAMAGES OR ANY DAMAGES
 * WHATSOEVER RESULTING FROM LOSS OF USE, DATA OR PROFITS, WHETHER IN AN
 * ACTION OF CONTRACT, NEGLIGENCE OR OTHER TORTIOUS ACTION, ARISING OUT OF
 * OR IN CONNECTION WITH THE USE OR PERFORMANCE OF THIS SOFTWARE.
 */
package org.fusesource.scalate.scaml

import org.fusesoruce.scalate.haml._
import java.util.regex.Pattern
import java.net.URI
import org.fusesource.scalate._
import collection.mutable.LinkedHashMap
import scala.util.parsing.input.CharSequenceReader

/**
 * Generates a scala class given a HAML document
 *
 * @author <a href="http://hiramchirino.com">Hiram Chirino</a>
 */
class ScamlCodeGenerator extends AbstractCodeGenerator[Statement] {

  private class SourceBuilder extends AbstractSourceBuilder[Statement] {

    val text_buffer = new StringBuffer
    var element_level = 0
    var pending_newline = false
    var suppress_indent = false
    var in_html_comment = false

    def write_indent() = {
      if( pending_newline ) {
        text_buffer.append("\n")
        pending_newline=false;
      }
      if( suppress_indent ) {
        suppress_indent=false
      } else {
        text_buffer.append(indent_string)
      }
    }

    def indent_string() = {
      val rc = new StringBuilder
      for( i <- 0 until element_level ) {
        rc.append("  ")
      }
      rc.toString
    }

    def trim_whitespace() = {
      pending_newline=false
      suppress_indent=true
    }

    def write_text(value:String) = {
      text_buffer.append(value)
    }

    def write_nl() = {
      pending_newline=true
    }

    def flush_text() = {
      if( pending_newline ) {
        text_buffer.append("\n")
        pending_newline=false;
      }
      if( text_buffer.length > 0 ) {
        this << "$_scalate_$_context << ( "+asString(text_buffer.toString)+" );"
        text_buffer.setLength(0)
      }
    }

    def generate(statements:List[Statement]):Unit = {
      this << "import _root_.org.fusesource.scalate.util.RenderHelper.{preserve=>$_scalate_$_preserve, indent=>$_scalate_$_indent}"
      generate_no_flush(statements)
      flush_text
    }

    def generate_no_flush(statements:List[Statement]):Unit = {

      val bindings = statements.flatMap {
        case attribute: Attribute => List(Binding(attribute.name, attribute.className, attribute.autoImport, attribute.defaultValue))
        case _ => Nil
      }

      generateBindings(bindings) {
        statements.foreach(statement=>{
          generate(statement)
        })
      }
    }

    def generate(statement:Statement):Unit = {
      statement match {
        case s:Attribute=> {
        }
        case s:ScamlComment=> {
          generate(s)
        }
        case s:TextExpression=> {
          write_indent
          generate(s)
          write_nl
        }
        case s:HtmlComment=> {
          generate(s)
        }
        case s:Element=> {
          generate(s)
        }
        case s:Executed=> {
          generate(s)
        }
        case s:FilterStatement=> {
          generate(s)
        }
        case s:Doctype=>{
          generate(s)
        }
      }
    }

    def generate(statement:Doctype):Unit = {
      write_indent
      statement.line match {
        case List("XML")=>
          write_text("<?xml version='1.0' encoding='utf-8' ?>")
        case List("XML", encoding)=>
          write_text("<?xml version='1.0' encoding='"+encoding+"' ?>")
        case _=>
          ScamlOptions.format match {
            case ScamlOptions.Format.xhtml=>
              statement.line match {
                case List("Strict")=>
                  write_text("""<!DOCTYPE html PUBLIC "-//W3C//DTD XHTML 1.0 Strict//EN" "http://www.w3.org/TR/xhtml1/DTD/xhtml1-strict.dtd">""")
                case List("Frameset")=>
                  write_text("""<!DOCTYPE html PUBLIC "-//W3C//DTD XHTML 1.0 Frameset//EN" "http://www.w3.org/TR/xhtml1/DTD/xhtml1-frameset.dtd">""")
                case List("5")=>
                  write_text("""<!DOCTYPE html>""")
                case List("1.1")=>
                  write_text("""<!DOCTYPE html PUBLIC "-//W3C//DTD XHTML 1.1//EN" "http://www.w3.org/TR/xhtml11/DTD/xhtml11.dtd">""")
                case List("Basic")=>
                  write_text("""<!DOCTYPE html PUBLIC "-//W3C//DTD XHTML Basic 1.1//EN" "http://www.w3.org/TR/xhtml-basic/xhtml-basic11.dtd"> """)
                case List("Mobile")=>
                  write_text("""<!DOCTYPE html PUBLIC "-//WAPFORUM//DTD XHTML Mobile 1.2//EN" "http://www.openmobilealliance.org/tech/DTD/xhtml-mobile12.dtd">""")
                case _=>
                  write_text("""<!DOCTYPE html PUBLIC "-//W3C//DTD XHTML 1.0 Transitional//EN" "http://www.w3.org/TR/xhtml1/DTD/xhtml1-transitional.dtd">""")
              }
            case ScamlOptions.Format.html4=>
              statement.line match {
                case List("Strict")=>
                  write_text("""<!DOCTYPE html PUBLIC "-//W3C//DTD HTML 4.01//EN" "http://www.w3.org/TR/html4/strict.dtd">""")
                case List("Frameset")=>
                  write_text("""<!DOCTYPE html PUBLIC "-//W3C//DTD HTML 4.01 Frameset//EN" "http://www.w3.org/TR/html4/frameset.dtd">""")
                case _=>
                  write_text("""<!DOCTYPE html PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN" "http://www.w3.org/TR/html4/loose.dtd">""")
              }
            case ScamlOptions.Format.html5=>
              write_text("""<!DOCTYPE html>""")
            
          }
      }
      write_nl
    }

    def generate(statement:FilterStatement):Unit = {
      if( statement.flags.contains("&") && statement.flags.contains("!") ) {
        throw new InvalidSyntaxException("Cannot use both the '&' and '!' filter flags together.", statement.pos);
      }

      val preserve = statement.flags.contains("~")
      val interpolate = statement.flags.contains("&") || statement.flags.contains("!")
      val sanitize = interpolate && statement.flags.contains("&")

      var content = statement.body.mkString("\n")

      var text:TextExpression = if( interpolate ) {
        val p = new ScamlParser()
        p.parse(p.literal_text(Some(sanitize)), content)
      } else {
        LiteralText(List(content), Some(false))
      }

      write_indent
      flush_text

      var prefix = "$_scalate_$_context << ( "
      var suffix = ");"

      if( preserve ) {
        prefix += "$_scalate_$_preserve (  "
        suffix = ") " + suffix;
      } else {
        prefix += "$_scalate_$_indent ( "+asString(indent_string())+", "
        suffix = ") " + suffix;
      }

      for ( f <- statement.filters ) {
        prefix += "$_scalate_$_context.render ( _root_.org.fusesource.scalate.FilterRequest("+asString(f)+", "
        suffix = ") ) " + suffix;
      }

      this << prefix + "$_scalate_$_context.capture { "
      indent {
        generate(text)
        flush_text
      }
      this << "} "+suffix
      write_nl
    }


    def generate(statement:TextExpression):Unit = {


      statement match {
        case s:LiteralText=> {
          var literal=true;
          for( part <- s.text ) {
            // alternate between rendering literal and interpolated text
            if( literal ) {
              write_text(part)
              literal=false
            } else {
              flush_text
              val method = if(s.sanitise.getOrElse(ScamlOptions.escape_html)) {
                "$_scalate_$_context <<< ( "
              } else {
                "$_scalate_$_context << ( "
              }
              this << method+part+" );"
              literal=true
            }
          }
        }
        case s:EvaluatedText=> {
          flush_text

          var prefix = if(s.sanitise.getOrElse(ScamlOptions.escape_html)) {
            "$_scalate_$_context <<< ("
          } else {
            "$_scalate_$_context << ("
          }

          var suffix = ");"

          if( s.preserve ) {
            prefix += " $_scalate_$_preserve ( $_scalate_$_context.render ("
            suffix = ") ) " + suffix;
          } else {
            prefix += " $_scalate_$_indent ( "+asString(indent_string())+", $_scalate_$_context.render ("
            suffix = ") ) " + suffix;
          }

          if( s.body.isEmpty ) {
            this << prefix+s.code+suffix
          } else {
            this << prefix+s.code+" {"
            indent {
              generate(s.body)
            }
            this << "} " + suffix
          }
        }
      }
    }

    def generate(statement:Executed):Unit = {
      flush_text
      if( statement.body.isEmpty ) {
        this << statement.code.getOrElse("")
      } else {
        this << statement.code.getOrElse("") + "{"
        indent {
          generate_no_flush(statement.body)
          flush_text
        }
        this << "}"
      }
    }

    def generate(statement:HtmlComment):Unit = {
      //  case class HtmlComment(conditional:Option[String], text:Option[String], body:List[Statement]) extends Statement
      var prefix = "<!--"
      var suffix = "-->"
      if( statement.conditional.isDefined ) {
        prefix = "<!--["+statement.conditional.get+"]>"
        suffix = "<![endif]-->"
      }

      // To support comment within comment blocks.
      if( in_html_comment ) {
        prefix = "" 
        suffix = ""
      } else {
        in_html_comment = true
      }


      statement match {
        case HtmlComment(_, text, List()) => {
          write_indent
          write_text(prefix+" ")
          write_text(text.getOrElse(""))
          write_text(" "+suffix)
          write_nl
        }
        case HtmlComment(_, None, list) => {
          write_indent
          write_text(prefix)
          write_nl

          element_level += 1
          generate_no_flush(list)
          element_level -= 1

          write_indent
          write_text(suffix)
          write_nl
        }
        case _ => throw new IllegalArgumentException("Syntax error on line "+statement.pos.line+": Illegal nesting: content can't be both given on the same line as html comment and nested within it.");
      }

      if( prefix.length!= 0 ) {
        in_html_comment = false
      }
    }


    def generate(statement:ScamlComment):Unit = {
      flush_text
      statement match {
        case ScamlComment(text, List()) => {
          this << "//" + text.getOrElse("")
        }
        case ScamlComment(text, list) => {
          this << "/*" + text.getOrElse("")
          list.foreach(x=>{
            this << " * " + x
          })
          this << " */"
        }
      }
    }
    
    def isAutoClosed(statement:Element) = {
      statement.text == None && statement.body.isEmpty &&
      statement.tag.isDefined && ScamlOptions.autoclose.contains(statement.tag.get)
    }

    def generate(statement:Element):Unit = {
      var tag = statement.tag.getOrElse("div");
      var prefix = "<"+tag+attributes(statement.attributes)+">"
      var suffix = "</"+tag+">"

      if( statement.close || isAutoClosed(statement) ) {
        if( statement.text.isDefined || !statement.body.isEmpty ) {
          throw new IllegalArgumentException("Syntax error on line "+statement.pos.line+": Illegal nesting: content can't be given on the same line as html element or nested within it if the tag is closed.");
        }
        prefix = "<"+tag+attributes(statement.attributes)+"/>"
        suffix = ""
      }

      statement.trim match {
        case Some(Trim.Outer)=>{
        }
        case Some(Trim.Inner)=>{}
        case Some(Trim.Both)=>{}
        case _ => {}
      }

      def outer_trim = statement.trim match {
        case Some(Trim.Outer)=>{ trim_whitespace; true}
        case Some(Trim.Both)=>{ trim_whitespace; true}
        case _ => { false}
      }

      def inner_trim = statement.trim match {
        case Some(Trim.Inner)=>{ trim_whitespace; true}
        case Some(Trim.Both)=>{ trim_whitespace; true}
        case _ => { false }
      }
      
      statement match {
        case Element(_,_,text,List(),_,_) => {
          outer_trim
          write_indent
          write_text(prefix)
          generate(text.getOrElse(LiteralText(List(""), Some(false))))
          write_text(suffix)
          write_nl
          outer_trim
        }
        case Element(_,_,None,list,_,_) => {
          outer_trim
          write_indent
          write_text(prefix)
          write_nl

          if( !inner_trim ) {
            element_level += 1
          }
          generate_no_flush(list)
          if( !inner_trim ) {
            element_level -= 1
          }

          write_indent
          write_text(suffix)
          write_nl
          outer_trim
        }
        case _ => throw new IllegalArgumentException("Syntax error on line "+statement.pos.line+": Illegal nesting: content can't be both given on the same line as html element and nested within it.");
      }
    }

    def attributes(entries: List[(Any,Any)]) = {
      val (entries_class, tmp) = entries.partition{x=>{ x._1 match { case "class" => true; case _=> false} } }
      val (entries_id, entries_rest) = tmp.partition{x=>{ x._1 match { case "id" => true; case _=> false} } }
      var map = LinkedHashMap[Any,Any]( )

      if( !entries_id.isEmpty ) {
        map += "id" -> entries_id.last._2
      }

      if( !entries_class.isEmpty ) {
        val value = entries_class.map(x=>x._2).mkString(" ")
        map += "class"->value
      }

      entries_rest.foreach{ me => map += me._1 -> me._2 }

      map.foldLeft(""){ case (r,e)=>r+" "+eval(e._1)+"='"+eval(e._2)+"'"}
    }

    def eval(expression:Any) = {
      expression match {
        case s:String=>s
        case _=> throw new UnsupportedOperationException("don't know how to eval: "+expression);
      }
    }

  }


  override def generate(engine:TemplateEngine, uri:String, bindings:List[Binding]): Code = {

    val hamlSource = engine.resourceLoader.load(uri)
    val (packageName, className) = extractPackageAndClassNames(uri)
    val statements = (new ScamlParser).parse(hamlSource)

    val builder = new SourceBuilder()
    builder.generate(packageName, className, bindings, statements)
    Code(this.className(uri), builder.code, Set(uri))

  }



}