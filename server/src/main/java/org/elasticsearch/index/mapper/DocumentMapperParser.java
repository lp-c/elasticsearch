/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.index.mapper;

import org.elasticsearch.Version;
import org.elasticsearch.common.Nullable;
import org.elasticsearch.common.compress.CompressedXContent;
import org.elasticsearch.common.time.DateFormatter;
import org.elasticsearch.common.xcontent.NamedXContentRegistry;
import org.elasticsearch.common.xcontent.XContentHelper;
import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.index.IndexSettings;
import org.elasticsearch.index.query.QueryShardContext;
import org.elasticsearch.index.similarity.SimilarityService;
import org.elasticsearch.indices.mapper.MapperRegistry;
import org.elasticsearch.script.ScriptService;

import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.function.Supplier;

public class DocumentMapperParser {

    final MapperService mapperService;
    private final NamedXContentRegistry xContentRegistry;
    private final SimilarityService similarityService;
    private final Supplier<QueryShardContext> queryShardContextSupplier;

    private final RootObjectMapper.TypeParser rootObjectTypeParser = new RootObjectMapper.TypeParser();

    private final Version indexVersionCreated;

    private final Map<String, Mapper.TypeParser> typeParsers;
    private final Map<String, MetadataFieldMapper.TypeParser> rootTypeParsers;
    private final ScriptService scriptService;

    public DocumentMapperParser(IndexSettings indexSettings, MapperService mapperService, NamedXContentRegistry xContentRegistry,
            SimilarityService similarityService, MapperRegistry mapperRegistry,
            Supplier<QueryShardContext> queryShardContextSupplier, ScriptService scriptService) {
        this.mapperService = mapperService;
        this.xContentRegistry = xContentRegistry;
        this.similarityService = similarityService;
        this.queryShardContextSupplier = queryShardContextSupplier;
        this.scriptService = scriptService;
        this.typeParsers = mapperRegistry.getMapperParsers();
        this.indexVersionCreated = indexSettings.getIndexVersionCreated();
        this.rootTypeParsers = mapperRegistry.getMetadataMapperParsers(indexVersionCreated);
    }

    public Mapper.TypeParser.ParserContext parserContext() {
        return new Mapper.TypeParser.ParserContext(similarityService::getSimilarity, mapperService,
                typeParsers::get, indexVersionCreated, queryShardContextSupplier, null, scriptService);
    }

    public Mapper.TypeParser.ParserContext parserContext(DateFormatter dateFormatter) {
        return new Mapper.TypeParser.ParserContext(similarityService::getSimilarity, mapperService,
            typeParsers::get, indexVersionCreated, queryShardContextSupplier, dateFormatter, scriptService);
    }

    @SuppressWarnings("unchecked")
    public DocumentMapper parse(@Nullable String type, CompressedXContent source) throws MapperParsingException {
        Map<String, Object> mapping = null;
        if (source != null) {
            mapping = XContentHelper.convertToMap(source.compressedReference(), true, XContentType.JSON).v2();
            if (mapping.isEmpty()) {
                if (type == null) {
                    throw new MapperParsingException("malformed mapping, no type name found");
                }
            } else {
                String rootName = mapping.keySet().iterator().next();
                if (type == null || type.equals(rootName) || mapperService.resolveDocumentType(type).equals(rootName)) {
                    type = rootName;
                    mapping = (Map<String, Object>) mapping.get(rootName);
                }
            }
        }
        if (mapping == null) {
            mapping = new HashMap<>();
        }
        return parse(type, mapping);
    }

    @SuppressWarnings({"unchecked"})
    private DocumentMapper parse(String type, Map<String, Object> mapping) throws MapperParsingException {
        if (type == null) {
            throw new MapperParsingException("Failed to derive type");
        }

        Mapper.TypeParser.ParserContext parserContext = parserContext();
        // parse RootObjectMapper
        DocumentMapper.Builder docBuilder = new DocumentMapper.Builder(
                (RootObjectMapper.Builder) rootObjectTypeParser.parse(type, mapping, parserContext), mapperService);
        Iterator<Map.Entry<String, Object>> iterator = mapping.entrySet().iterator();
        // parse DocumentMapper
        while(iterator.hasNext()) {
            Map.Entry<String, Object> entry = iterator.next();
            String fieldName = entry.getKey();
            Object fieldNode = entry.getValue();

            MetadataFieldMapper.TypeParser typeParser = rootTypeParsers.get(fieldName);
            if (typeParser != null) {
                iterator.remove();
                if (false == fieldNode instanceof Map) {
                    throw new IllegalArgumentException("[_parent] must be an object containing [type]");
                }
                Map<String, Object> fieldNodeMap = (Map<String, Object>) fieldNode;
                docBuilder.put(typeParser.parse(fieldName, fieldNodeMap, parserContext));
                fieldNodeMap.remove("type");
                checkNoRemainingFields(fieldName, fieldNodeMap, parserContext.indexVersionCreated());
            }
        }

        Map<String, Object> meta = (Map<String, Object>) mapping.remove("_meta");
        if (meta != null) {
            /*
             * It may not be required to copy meta here to maintain immutability but the cost is pretty low here.
             *
             * Note: this copy can not be replaced by Map#copyOf because we rely on consistent serialization order since we do byte-level
             * checks on the mapping between what we receive from the master and what we have locally. As Map#copyOf is not necessarily
             * the same underlying map implementation, we could end up with a different iteration order. For reference, see
             * MapperService#assertSerializtion and GitHub issues #10302 and #10318.
             *
             * Do not change this to Map#copyOf or any other method of copying meta that could change the iteration order.
             *
             * TODO:
             *  - this should almost surely be a copy as a LinkedHashMap to have the ordering guarantees that we are relying on
             *  - investigate the above note about whether or not we really need to be copying here, the ideal outcome would be to not
             */
            docBuilder.meta(Collections.unmodifiableMap(new HashMap<>(meta)));
        }

        checkNoRemainingFields(mapping, parserContext.indexVersionCreated(), "Root mapping definition has unsupported parameters: ");

        return docBuilder.build(mapperService.getIndexSettings(), mapperService.documentMapperParser(), mapperService.getIndexAnalyzers());
    }

    public static void checkNoRemainingFields(String fieldName, Map<?, ?> fieldNodeMap, Version indexVersionCreated) {
        checkNoRemainingFields(fieldNodeMap, indexVersionCreated,
                "Mapping definition for [" + fieldName + "] has unsupported parameters: ");
    }

    public static void checkNoRemainingFields(Map<?, ?> fieldNodeMap, Version indexVersionCreated, String message) {
        if (!fieldNodeMap.isEmpty()) {
            throw new MapperParsingException(message + getRemainingFields(fieldNodeMap));
        }
    }

    private static String getRemainingFields(Map<?, ?> map) {
        StringBuilder remainingFields = new StringBuilder();
        for (Object key : map.keySet()) {
            remainingFields.append(" [").append(key).append(" : ").append(map.get(key)).append("]");
        }
        return remainingFields.toString();
    }

    NamedXContentRegistry getXContentRegistry() {
        return xContentRegistry;
    }
}
