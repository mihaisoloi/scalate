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
package org.fusesource.scalate.shaml

import org.fusesoruce.scalate.haml._
import java.util.regex.Pattern
import java.net.URI
import org.fusesource.scalate._

/**
 * Generates a scala class given a HAML document
 *
 * @author <a href="http://hiramchirino.com">Hiram Chirino</a>
 */
class ShamlCodeGenerator extends AbstractCodeGenerator[Statement] {

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
        for( i <- 0 until element_level ) {
          text_buffer.append("  ")
        }
      }
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
      generate_no_flush(statements)
      flush_text
    }

    def generate_no_flush(statements:List[Statement]):Unit = {
      statements.foreach(statement=>{
        generate(statement)
      })
    }

    def generate(statement:Statement):Unit = {
      statement match {
        case s:Attribute=> {
        }
        case s:ShamlComment=> {
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
        case s:Filter=> {
          throw new UnsupportedOperationException("filters not yet implemented.");
        }
      }
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
              val method = s.sanitise match {
                case None => { "org.fusesource.scalate.shaml.ShamlOptions.write( $_scalate_$_context, " }
                case Some(true) => { "$_scalate_$_context <<< ( " }
                case Some(false) => { "$_scalate_$_context << ( " }
              }
              this << method+part+");"
              literal=true
            }
          }
        }
        case s:EvaluatedText=> {
          flush_text
          val method = s.sanitise match {
            case None => { "org.fusesource.scalate.shaml.ShamlOptions.write( $_scalate_$_context, " }
            case Some(true) => { "$_scalate_$_context <<< ( " }
            case Some(false) => { "$_scalate_$_context << ( " }
          }
          this << method+s.code+" );"
        }
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
          write_text(prefix)
          write_text(text.getOrElse(""))
          write_text(suffix)
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


    def generate(statement:ShamlComment):Unit = {
      flush_text
      statement match {
        case ShamlComment(text, List()) => {
          this << "//" + text.getOrElse("")
        }
        case ShamlComment(text, list) => {
          this << "/*" + text.getOrElse("")
          list.foreach(x=>{
            this << " * " + x
          })
          this << " */"
        }
      }
    }
    
    def generate(statement:Element):Unit = {
      var tag = statement.tag.getOrElse("div");
      var prefix = "<"+tag+attributes(statement.attributes)+">"
      var suffix = "</"+tag+">"

      if( statement.close ) {
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
        case Some(Trim.Outer)=>{ trim_whitespace}
        case Some(Trim.Both)=>{ trim_whitespace}
        case _ => {}
      }

      def inner_trim = statement.trim match {
        case Some(Trim.Inner)=>{ trim_whitespace}
        case Some(Trim.Both)=>{ trim_whitespace}
        case _ => {}
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

          inner_trim
          element_level += 1
          generate_no_flush(list)
          element_level -= 1
          inner_trim

          write_indent
          write_text(suffix)
          write_nl
          outer_trim
        }
        case _ => throw new IllegalArgumentException("Syntax error on line "+statement.pos.line+": Illegal nesting: content can't be both given on the same line as html element and nested within it.");
      }
    }

    def generate(statement:Executed):Unit = {
      flush_text
      statement match {
        case Executed(code, List()) => {
          this << code.getOrElse("")
        }
        case Executed(code, list) => {
          this << code.getOrElse("") + "{"
          indent {
            generate_no_flush(list)
          }
          this << "}"
        }
      }
    }

    def attributes(entries: List[(Any,Any)]) = {
      val (entries_class, entries_rest) = entries.partition{x=>{ x._1 match { case "class" => true; case _=> false} } }
      var map = Map( entries_rest: _* )

      if( !entries_class.isEmpty ) {
        val value = entries_class.map(x=>x._2).mkString(" ")
        map += "class"->value
      }
      map.foldLeft(""){ case (r,e)=>r+" "+eval(e._1)+"=\""+eval(e._2)+"\""}
    }

    def eval(expression:Any) = {
      expression match {
        case s:String=>s
        case _=> throw new UnsupportedOperationException("don't know how to eval: "+expression);
      }
    }

  }


  override def generate(engine:TemplateEngine, uri:String, args:List[TemplateArg]): Code = {

    val hamlSource = engine.resourceLoader.load(uri)
    val (packageName, className) = extractPackageAndClassNames(uri)
    val statements = (new ShamlParser).parse(hamlSource)

    val template_args = statements.flatMap {
      case attribute: Attribute => List(TemplateArg(attribute.name, attribute.className, attribute.autoImport, attribute.defaultValue))
      case _ => Nil
    }

    val builder = new SourceBuilder()
    builder.generate(packageName, className, args:::template_args, statements)
    Code(this.className(uri, args), builder.code, Set())

  }



}
