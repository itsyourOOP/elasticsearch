/*
 * Licensed to Elastic Search and Shay Banon under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. Elastic Search licenses this
 * file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.search.facet.datehistogram;

import org.apache.lucene.index.IndexReader;
import org.elasticsearch.common.joda.time.MutableDateTime;
import org.elasticsearch.common.trove.map.hash.TLongDoubleHashMap;
import org.elasticsearch.common.trove.map.hash.TLongLongHashMap;
import org.elasticsearch.index.cache.field.data.FieldDataCache;
import org.elasticsearch.index.field.data.FieldDataType;
import org.elasticsearch.index.field.data.longs.LongFieldData;
import org.elasticsearch.index.mapper.FieldMapper;
import org.elasticsearch.index.mapper.MapperService;
import org.elasticsearch.script.SearchScript;
import org.elasticsearch.search.facet.AbstractFacetCollector;
import org.elasticsearch.search.facet.Facet;
import org.elasticsearch.search.facet.FacetPhaseExecutionException;
import org.elasticsearch.search.internal.SearchContext;

import java.io.IOException;
import java.util.Map;

/**
 * A histogram facet collector that uses the same field as the key as well as the
 * value.
 *
 * @author kimchy (shay.banon)
 */
public class ValueScriptDateHistogramFacetCollector extends AbstractFacetCollector {

    private final String indexFieldName;

    private final MutableDateTime dateTime;

    private final DateHistogramFacet.ComparatorType comparatorType;

    private final FieldDataCache fieldDataCache;

    private final FieldDataType fieldDataType;

    private LongFieldData fieldData;

    private final SearchScript valueScript;

    private final DateHistogramProc histoProc;

    public ValueScriptDateHistogramFacetCollector(String facetName, String fieldName, String scriptLang, String valueScript, Map<String, Object> params, MutableDateTime dateTime, long interval, DateHistogramFacet.ComparatorType comparatorType, SearchContext context) {
        super(facetName);
        this.dateTime = dateTime;
        this.comparatorType = comparatorType;
        this.fieldDataCache = context.fieldDataCache();

        MapperService.SmartNameFieldMappers smartMappers = context.mapperService().smartName(fieldName);
        if (smartMappers == null || !smartMappers.hasMapper()) {
            throw new FacetPhaseExecutionException(facetName, "No mapping found for field [" + fieldName + "]");
        }

        // add type filter if there is exact doc mapper associated with it
        if (smartMappers.hasDocMapper()) {
            setFilter(context.filterCache().cache(smartMappers.docMapper().typeFilter()));
        }

        this.valueScript = context.scriptService().search(context.lookup(), scriptLang, valueScript, params);

        FieldMapper mapper = smartMappers.mapper();

        indexFieldName = mapper.names().indexName();
        fieldDataType = mapper.fieldDataType();

        if (interval == 1) {
            histoProc = new DateHistogramProc(this.valueScript);
        } else {
            histoProc = new IntervalDateHistogramProc(interval, this.valueScript);
        }
    }

    @Override protected void doCollect(int doc) throws IOException {
        fieldData.forEachValueInDoc(doc, dateTime, histoProc);
    }

    @Override protected void doSetNextReader(IndexReader reader, int docBase) throws IOException {
        fieldData = (LongFieldData) fieldDataCache.cache(fieldDataType, reader, indexFieldName);
        valueScript.setNextReader(reader);
    }

    @Override public Facet facet() {
        return new InternalCountAndTotalDateHistogramFacet(facetName, comparatorType, histoProc.counts(), histoProc.totals());
    }

    public static class DateHistogramProc implements LongFieldData.DateValueInDocProc {

        protected final SearchScript valueScript;

        protected final TLongLongHashMap counts = new TLongLongHashMap();

        protected final TLongDoubleHashMap totals = new TLongDoubleHashMap();

        public DateHistogramProc(SearchScript valueScript) {
            this.valueScript = valueScript;
        }

        @Override public void onValue(int docId, MutableDateTime dateTime) {
            valueScript.setNextDocId(docId);

            long time = dateTime.getMillis();
            counts.adjustOrPutValue(time, 1, 1);
            double scriptValue = valueScript.runAsDouble();
            totals.adjustOrPutValue(time, scriptValue, scriptValue);
        }


        public TLongLongHashMap counts() {
            return counts;
        }

        public TLongDoubleHashMap totals() {
            return totals;
        }
    }

    public static class IntervalDateHistogramProc extends DateHistogramProc {

        private final long interval;

        public IntervalDateHistogramProc(long interval, SearchScript valueScript) {
            super(valueScript);
            this.interval = interval;
        }


        @Override public void onValue(int docId, MutableDateTime dateTime) {
            valueScript.setNextDocId(docId);

            long bucket = CountDateHistogramFacetCollector.bucket(dateTime.getMillis(), interval);
            counts.adjustOrPutValue(bucket, 1, 1);
            double scriptValue = valueScript.runAsDouble();
            totals.adjustOrPutValue(bucket, scriptValue, scriptValue);
        }

        public TLongLongHashMap counts() {
            return counts;
        }

        public TLongDoubleHashMap totals() {
            return totals;
        }
    }
}