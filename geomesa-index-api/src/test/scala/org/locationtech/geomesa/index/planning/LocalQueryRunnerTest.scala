/***********************************************************************
 * Copyright (c) 2013-2020 Commonwealth Computer Research, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Apache License, Version 2.0
 * which accompanies this distribution and is available at
 * http://www.opensource.org/licenses/apache2.0.php.
 ***********************************************************************/

package org.locationtech.geomesa.index.planning

import java.io.ByteArrayInputStream
import java.util.Date

import org.geotools.data.Query
import org.geotools.filter.SortByImpl
import org.junit.runner.RunWith
import org.locationtech.geomesa.arrow.{DelegatingAllocationListener, MatchingAllocationListener}
import org.locationtech.geomesa.arrow.io.SimpleFeatureArrowFileReader
import org.locationtech.geomesa.features.ScalaSimpleFeature
import org.locationtech.geomesa.index.conf.QueryHints
import org.locationtech.geomesa.index.planning.QueryInterceptor.QueryInterceptorFactory
import org.locationtech.geomesa.index.stats.NoopStats
import org.locationtech.geomesa.utils.collection.{CloseableIterator, SelfClosingIterator}
import org.locationtech.geomesa.utils.geotools.SimpleFeatureTypes
import org.locationtech.geomesa.utils.io.WithClose
import org.opengis.feature.simple.{SimpleFeature, SimpleFeatureType}
import org.opengis.filter.Filter
import org.opengis.filter.sort.SortOrder
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner

@RunWith(classOf[JUnitRunner])
class LocalQueryRunnerTest extends Specification {
  sequential

  import org.locationtech.geomesa.filter.ff

  val listener = new MatchingAllocationListener
  DelegatingAllocationListener.addListener(listener)

  val typeName = "memory"
  val spec = "name:String,age:Int,dtg:Date,*geom:Point:srid=4326"

  val sft = SimpleFeatureTypes.createType(typeName, spec)

  val features = Seq(
    Array("alice",   20,   "2012-01-01T12:00:00.000Z", "POINT(45.0 49.0)"),
    Array("bill",    20,   "2013-01-01T12:00:00.000Z", "POINT(46.0 49.0)"),
    Array("bob",     30,   "2014-01-01T12:00:00.000Z", "POINT(47.0 49.0)"),
    Array("charles", null, "2014-01-01T12:30:00.000Z", "POINT(48.0 49.0)")
  ).map {
    entry => ScalaSimpleFeature.create(sft, entry.head.toString, entry: _*)
  }

  val runner: LocalQueryRunner = new LocalQueryRunner(NoopStats, None) {
    override protected val name: String = "test-runner"
    override protected val interceptors: QueryInterceptorFactory = QueryInterceptorFactory.empty()
    override protected def features(sft: SimpleFeatureType, filter: Option[Filter]): CloseableIterator[SimpleFeature] = {
      filter match {
        case None    => CloseableIterator(LocalQueryRunnerTest.this.features.iterator)
        case Some(f) => CloseableIterator(LocalQueryRunnerTest.this.features.iterator.filter(f.evaluate))
      }
    }
  }

  val failingRunner: LocalQueryRunner = new LocalQueryRunner(NoopStats, None) {
    override protected val name: String = "test-runner"
    override protected val interceptors: QueryInterceptorFactory = QueryInterceptorFactory.empty()
    override protected def features(sft: SimpleFeatureType, filter: Option[Filter]): CloseableIterator[SimpleFeature] = {
      val iter = filter match {
        case None    => CloseableIterator(LocalQueryRunnerTest.this.features.iterator)
        case Some(f) => CloseableIterator(LocalQueryRunnerTest.this.features.iterator.filter(f.evaluate))
      }
      new Iterator[SimpleFeature] {
        private val internal = iter
        override def hasNext: Boolean = {
          if (internal.hasNext) {
            true
          } else {
            throw new NoSuchElementException("No more elements!")
          }
        }

        override def next(): SimpleFeature = {
          internal.next()
        }
      }
    }
  }

  "InMemoryQueryRunner" should {
    "not sort" in {
      runner.runQuery(sft, new Query("memory")).map(ScalaSimpleFeature.copy).toSeq mustEqual features
    }

    "sort by an attribute" in {
      val q = new Query("memory")
      q.setSortBy(Array(new SortByImpl(ff.property("name"), SortOrder.ASCENDING)))
      runner.runQuery(sft, q).map(ScalaSimpleFeature.copy).toSeq mustEqual features
      q.setSortBy(Array(new SortByImpl(ff.property("name"), SortOrder.DESCENDING)))
      runner.runQuery(sft, q).map(ScalaSimpleFeature.copy).toSeq mustEqual features.reverse
    }

    "sort by multiple attributes" in {
      val q = new Query("memory")
      q.setSortBy(Array(new SortByImpl(ff.property("age"), SortOrder.ASCENDING),
        new SortByImpl(ff.property("name"), SortOrder.DESCENDING)))
      runner.runQuery(sft, q).map(ScalaSimpleFeature.copy).toSeq mustEqual Seq(features(3), features(1), features(0), features(2))
    }

    "sort by projections" in {
      val q = new Query("memory", Filter.INCLUDE, Array("derived=strConcat('aa', name)", "geom"))
      q.setSortBy(Array(new SortByImpl(ff.property("derived"), SortOrder.DESCENDING)))
      runner.runQuery(sft, q).map(ScalaSimpleFeature.copy).map(_.getID).toSeq mustEqual features.reverse.map(_.getID)
    }

//    "query for arrow" in {
//      val q = new Query("memory", Filter.INCLUDE, Array("name", "dtg", "geom"))
//      val expected = runner.runQuery(sft, q).map(ScalaSimpleFeature.copy).toSeq.sortBy(_.getAttribute("dtg").asInstanceOf[Date])
//      q.getHints.put(QueryHints.ARROW_ENCODE, java.lang.Boolean.TRUE)
//      q.getHints.put(QueryHints.ARROW_SORT_FIELD, "dtg")
//      q.getHints.put(QueryHints.ARROW_DICTIONARY_FIELDS, "name")
//      // note: need to copy the features as the same object is re-used in the iterator
//      val bytes = WithClose(runner.runQuery(sft, q)) { iter =>
//        iter.map(_.getAttribute(0).asInstanceOf[Array[Byte]]).reduceLeftOption(_ ++ _).getOrElse(Array.empty[Byte])
//      }
//      WithClose(SimpleFeatureArrowFileReader.streaming(() => new ByteArrayInputStream(bytes))) { reader =>
//        SelfClosingIterator(reader.features()).map(ScalaSimpleFeature.copy).toSeq mustEqual expected
//      }
//    }

    //   hints.put(QueryHints.Internal.SKIP_REDUCE, java.lang.Boolean.TRUE)
    //

//    "query for arrow and not leak memory" in {
//      val q = new Query("memory", Filter.INCLUDE, Array("name", "dtg", "geom"))
//      //val expected = failingRunner.runQuery(sft, q).map(ScalaSimpleFeature.copy).toSeq.sortBy(_.getAttribute("dtg").asInstanceOf[Date])
//      q.getHints.put(QueryHints.ARROW_ENCODE, java.lang.Boolean.TRUE)
//      q.getHints.put(QueryHints.ARROW_SORT_FIELD, "dtg")
//      q.getHints.put(QueryHints.ARROW_DICTIONARY_FIELDS, "name")
//      // note: need to copy the features as the same object is re-used in the iterator
//      val bytes = WithClose(runner.runQuery(sft, q)) { iter =>
//        iter.map(_.getAttribute(0).asInstanceOf[Array[Byte]]).reduceLeftOption(_ ++ _).getOrElse(Array.empty[Byte])
//      }
//      listener.unmatchedAllocation.size mustEqual 0
//    }

//    "query for arrow and not leak memory with skip reduce" in {
//      val q = new Query("memory", Filter.INCLUDE, Array("name", "dtg", "geom"))
//      //val expected = failingRunner.runQuery(sft, q).map(ScalaSimpleFeature.copy).toSeq.sortBy(_.getAttribute("dtg").asInstanceOf[Date])
//      q.getHints.put(QueryHints.ARROW_ENCODE, java.lang.Boolean.TRUE)
//      q.getHints.put(QueryHints.ARROW_SORT_FIELD, "dtg")
//      q.getHints.put(QueryHints.ARROW_DICTIONARY_FIELDS, "name")
//      q.getHints.put(QueryHints.Internal.SKIP_REDUCE, java.lang.Boolean.TRUE)
//      // note: need to copy the features as the same object is re-used in the iterator
//      val bytes = WithClose(runner.runQuery(sft, q)) { iter =>
//        iter.map(_.getAttribute(0).asInstanceOf[Array[Byte]]).reduceLeftOption(_ ++ _).getOrElse(Array.empty[Byte])
//      }
//      listener.unmatchedAllocation.size mustEqual 0
//    }

    "Work in lots of configurations" in {
      val q = new Query("memory", Filter.INCLUDE, Array("name", "dtg", "geom"))
      q.getHints.put(QueryHints.ARROW_ENCODE, java.lang.Boolean.TRUE)
      q.getHints.put(QueryHints.ARROW_SORT_FIELD, "dtg")
      q.getHints.put(QueryHints.ARROW_DICTIONARY_FIELDS, "name")

      forall(Seq(java.lang.Boolean.TRUE, java.lang.Boolean.FALSE)) { skipReduce =>
        forall(Seq(java.lang.Boolean.TRUE, java.lang.Boolean.FALSE)) { doublePass =>
          forall(Seq(java.lang.Boolean.TRUE, java.lang.Boolean.FALSE)) { multiFile =>
            q.getHints.put(QueryHints.Internal.SKIP_REDUCE, skipReduce)
            q.getHints.put(QueryHints.ARROW_DOUBLE_PASS, doublePass)
            q.getHints.put(QueryHints.ARROW_MULTI_FILE, multiFile)

            // note: need to copy the features as the same object is re-used in the iterator
            try {
              val bytes = WithClose(failingRunner.runQuery(sft, q)) { iter =>
                iter.map(_.getAttribute(0).asInstanceOf[Array[Byte]]).reduceLeftOption(_ ++ _).getOrElse(Array.empty[Byte])
              }
            } catch {
              case exception: Exception => // Swallowing exception from intentially failing iterator.
            }
            println(s"Unmatched: ${listener.unmatchedAllocation.size} SkipReduce $skipReduce doublePass $doublePass MultiFile $multiFile.")
            listener.unmatchedAllocation.size mustEqual 0
          }
        }
      }
    }

    "query for arrow and not leak memory with skip reduce" in {
      val q = new Query("memory", Filter.INCLUDE, Array("name", "dtg", "geom"))
      q.getHints.put(QueryHints.ARROW_ENCODE, java.lang.Boolean.TRUE)
      q.getHints.put(QueryHints.ARROW_SORT_FIELD, "dtg")
      q.getHints.put(QueryHints.ARROW_DICTIONARY_FIELDS, "name")
      q.getHints.put(QueryHints.ARROW_MULTI_FILE, java.lang.Boolean.TRUE)
      // note: need to copy the features as the same object is re-used in the iterator
      try {
      val bytes = WithClose(failingRunner.runQuery(sft, q)) { iter =>
        iter.map(_.getAttribute(0).asInstanceOf[Array[Byte]]).reduceLeftOption(_ ++ _).getOrElse(Array.empty[Byte])
      }
      } catch {
        case exception: Exception => // Swallowing exception from intentially failing iterator.
      }
      listener.unmatchedAllocation.size mustEqual 0
    }
  }

}
