package org.apache.camel.component.cassandra

import org.apache.camel.impl.DefaultProducer
import CassandraProducer._
import org.apache.camel.builder.ExpressionBuilder
import grizzled.slf4j.Logger
import com.shorrockin.cascal.session._
import com.shorrockin.cascal.utils.Conversions._
import org.apache.camel.{Message, Exchange}
import org.apache.camel.component.cassandra.CassandraComponent._
import org.apache.camel.spi.DataFormat
import java.util.{UUID, Date}
import java.io.ByteArrayOutputStream
import reflect.BeanProperty
import collection.JavaConversions._
import com.shorrockin.cascal.model.Keyspace
import collection.JavaConversions

/**
 *
 */

class CassandraProducer(val endpoint: CassandraEndpoint) extends DefaultProducer(endpoint) {
  private val logger: Logger = Logger(classOf[CassandraProducer])
  @BeanProperty
  var keyspaceExtractor = defaultKeyspaceExtractor
  @BeanProperty
  var columnFamilyExtractor = defaultColumnFamilyExtractor
  @BeanProperty
  var superColumnExtractor = defaultSuperColumnExtractor
  @BeanProperty
  var columnExtractor = defaultColumnExtractor
  @BeanProperty
  var keyExtractor = defaultKeyExtractor
  @BeanProperty
  var valueExtractor = defaultValueExtractor
  overrideDefaultExtractors();

  def process(exchange: Exchange): Unit = {
    val exchanges = endpoint.batchCreator.get.createBatch(exchange)
    endpoint.withSession {
      session =>
        val inserts = JavaConversions.asList(exchanges).map {
          createInsertAndSetOutFor(_)
        }
        session.batch(inserts)
    }

    if (exchanges.size == 1 && exchanges.get(0).equals(exchange)) {
      //leave it alone
    } else {
      exchange.getOut.setBody(exchanges)
      exchange.getOut.setHeader(batchSizeHeader, exchanges.size)
    }

  }

  def createInsertAndSetOutFor(exchange: Exchange): Insert = {

    var keyspace: String = endpoint.keyspace.getOrElse(keyspaceExtractor.evaluate(exchange, classOf[String]))
    var columnfamily: String = endpoint.columnFamily.getOrElse(columnFamilyExtractor.evaluate(exchange, classOf[String]))
    var supercolumn: Option[Array[Byte]] = endpoint.superColumn match {
      case Some(sc) => Some(sc)
      case None => {
        superColumnExtractor.evaluate(exchange, classOf[Any]) match {
          case null => None
          case str => coerceToByteArray(str)
        }
      }
    }


    var column: Array[Byte] = null
    column = endpoint.column match {
      case Some(col) => col
      case None => coerceToByteArray(columnExtractor.evaluate(exchange, classOf[Any])).get
    }
    var key = endpoint.key.getOrElse(keyExtractor.evaluate(exchange, classOf[String]))
    var valueExtract: Any = valueExtractor.evaluate(exchange, classOf[Any])
    var value: Array[Byte] = new Array[Byte](0)

    endpoint.dataFormat match {
      case Some(format: DataFormat) => {
        val buffer = new ByteArrayOutputStream
        format.marshal(exchange, valueExtract, buffer)
        value = buffer.toByteArray
      }
      case None => {
        value = coerceToByteArray(valueExtract).getOrElse {
          //Else try to convert to byte[] with the valueExtractor, will throw NoSupportedConversion in worst case
          valueExtractor.evaluate(exchange, classOf[Array[Byte]])
        }

      }
    }


    val out: Message = exchange.getOut
    var insert: Insert = null
    supercolumn match {
      case Some(sc) => {
        log.debug("Inserting sueprcolumn keyspace:%s columnFamily:%s key:%s supercolumn:%s column:%s".format(keyspace, columnfamily, key, sc, column))
        insert = Insert(Keyspace(keyspace) \\ columnfamily \ key \ sc \ (column, value))
        out.setHeader(superColumnHeader, sc)
      }
      case None => {
        log.debug("Inserting standard column keyspace:%s columnFamily:%s key:%s column:%s".format(keyspace, columnfamily, key, column))
        insert = Insert(Keyspace(keyspace) \ columnfamily \ key \ (column, value))
      }
    }

    out.setHeader(keyspaceHeader, keyspace)
    out.setHeader(columnFamilyHeader, columnfamily)
    out.setHeader(columnHeader, column)
    out.setHeader(keyHeader, key)
    out.setBody(value)
    insert
  }


  private def coerceToByteArray(valueExtract: Any): Option[Array[Byte]] = {
    var value: Array[Byte] = null
    if(valueExtract.isInstanceOf[Array[Byte]]) value = valueExtract.asInstanceOf[Array[Byte]]
    else if (valueExtract.isInstanceOf[String]) value = bytes(valueExtract.asInstanceOf[String])
    else if (valueExtract.isInstanceOf[Array[Byte]]) value = valueExtract.asInstanceOf[Array[Byte]]
    else if (valueExtract.isInstanceOf[Long]) value = bytes(valueExtract.asInstanceOf[Long])
    else if (valueExtract.isInstanceOf[Date]) value = bytes(valueExtract.asInstanceOf[Date])
    else if (valueExtract.isInstanceOf[Float]) value = bytes(valueExtract.asInstanceOf[Float])
    else if (valueExtract.isInstanceOf[Double]) value = bytes(valueExtract.asInstanceOf[Double])
    else if (valueExtract.isInstanceOf[Int]) value = bytes(valueExtract.asInstanceOf[Int])
    else if (valueExtract.isInstanceOf[UUID]) value = bytes(valueExtract.asInstanceOf[UUID])

    Option(value)
  }

  private def overrideDefaultExtractors(): Unit = {
    endpoint.keyspaceExtractor match {
      case Some(ext) => keyspaceExtractor = ext
      case _ => None
    }
    endpoint.columnFamilyExtractor match {
      case Some(ext) => columnFamilyExtractor = ext
      case _ => None
    }
    endpoint.supercolumnExtractor match {
      case Some(ext) => superColumnExtractor = ext
      case _ => None
    }
    endpoint.columnExtractor match {
      case Some(ext) => columnExtractor = ext
      case _ => None
    }
    endpoint.keyExtractor match {
      case Some(ext) => keyExtractor = ext
      case _ => None
    }
    endpoint.valueExtractor match {
      case Some(ext) => valueExtractor = ext
      case _ => None
    }
  }

}

object CassandraProducer {
  def defaultKeyspaceExtractor = ExpressionBuilder.headerExpression(keyspaceHeader)

  def defaultColumnFamilyExtractor = ExpressionBuilder.headerExpression(columnFamilyHeader)

  def defaultSuperColumnExtractor = ExpressionBuilder.headerExpression(superColumnHeader)

  def defaultColumnExtractor = ExpressionBuilder.headerExpression(columnHeader)

  def defaultKeyExtractor = ExpressionBuilder.headerExpression(keyHeader)

  def defaultValueExtractor = ExpressionBuilder.bodyExpression()
}