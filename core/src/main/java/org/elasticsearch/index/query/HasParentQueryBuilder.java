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
package org.elasticsearch.index.query;

import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.join.ScoreMode;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.lucene.search.Queries;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.index.fielddata.plain.ParentChildIndexFieldData;
import org.elasticsearch.index.mapper.DocumentMapper;
import org.elasticsearch.index.mapper.internal.ParentFieldMapper;
import org.elasticsearch.index.query.support.InnerHitBuilder;

import java.io.IOException;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Builder for the 'has_parent' query.
 */
public class HasParentQueryBuilder extends AbstractQueryBuilder<HasParentQueryBuilder> {

    public static final String NAME = "has_parent";
    public static final boolean DEFAULT_SCORE = false;
    private final QueryBuilder query;
    private final String type;
    private boolean score = DEFAULT_SCORE;
    private InnerHitBuilder innerHit;

    /**
     * @param type  The parent type
     * @param query The query that will be matched with parent documents
     */
    public HasParentQueryBuilder(String type, QueryBuilder query) {
        if (type == null) {
            throw new IllegalArgumentException("[" + NAME + "] requires 'parent_type' field");
        }
        if (query == null) {
            throw new IllegalArgumentException("[" + NAME + "] requires 'query' field");
        }
        this.type = type;
        this.query = query;
    }

    public HasParentQueryBuilder(String type, QueryBuilder query, boolean score, InnerHitBuilder innerHit) {
        this(type, query);
        this.score = score;
        this.innerHit = innerHit;
        if (this.innerHit != null) {
            this.innerHit.setParentChildType(type);
            this.innerHit.setQuery(query);
        }
    }

    /**
     * Defines if the parent score is mapped into the child documents.
     */
    public HasParentQueryBuilder score(boolean score) {
        this.score = score;
        return this;
    }

    /**
     * Sets inner hit definition in the scope of this query and reusing the defined type and query.
     */
    public HasParentQueryBuilder innerHit(InnerHitBuilder innerHit) {
        this.innerHit = Objects.requireNonNull(innerHit);
        this.innerHit.setParentChildType(type);
        this.innerHit.setQuery(query);
        return this;
    }

    /**
     * Returns the query to execute.
     */
    public QueryBuilder query() {
        return query;
    }

    /**
     * Returns <code>true</code> if the parent score is mapped into the child documents
     */
    public boolean score() {
        return score;
    }

    /**
     * Returns the parents type name
     */
    public String type() {
        return type;
    }

    /**
     *  Returns inner hit definition in the scope of this query and reusing the defined type and query.
     */
    public InnerHitBuilder innerHit() {
        return innerHit;
    }

    @Override
    protected Query doToQuery(QueryShardContext context) throws IOException {
        Query innerQuery;
        String[] previousTypes = context.getTypes();
        context.setTypes(type);
        try {
            innerQuery = query.toQuery(context);
        } finally {
            context.setTypes(previousTypes);
        }

        if (innerQuery == null) {
            return null;
        }
        DocumentMapper parentDocMapper = context.getMapperService().documentMapper(type);
        if (parentDocMapper == null) {
            throw new QueryShardException(context, "[" + NAME + "] query configured 'parent_type' [" + type
                    + "] is not a valid type");
        }

        if (innerHit != null) {
            context.addInnerHit(innerHit);
        }

        Set<String> childTypes = new HashSet<>();
        ParentChildIndexFieldData parentChildIndexFieldData = null;
        for (DocumentMapper documentMapper : context.getMapperService().docMappers(false)) {
            ParentFieldMapper parentFieldMapper = documentMapper.parentFieldMapper();
            if (parentFieldMapper.active() && type.equals(parentFieldMapper.type())) {
                childTypes.add(documentMapper.type());
                parentChildIndexFieldData = context.getForField(parentFieldMapper.fieldType());
            }
        }

        if (childTypes.isEmpty()) {
            throw new QueryShardException(context, "[" + NAME + "] no child types found for type [" + type + "]");
        }

        Query childrenQuery;
        if (childTypes.size() == 1) {
            DocumentMapper documentMapper = context.getMapperService().documentMapper(childTypes.iterator().next());
            childrenQuery = documentMapper.typeFilter();
        } else {
            BooleanQuery.Builder childrenFilter = new BooleanQuery.Builder();
            for (String childrenTypeStr : childTypes) {
                DocumentMapper documentMapper = context.getMapperService().documentMapper(childrenTypeStr);
                childrenFilter.add(documentMapper.typeFilter(), BooleanClause.Occur.SHOULD);
            }
            childrenQuery = childrenFilter.build();
        }

        // wrap the query with type query
        innerQuery = Queries.filtered(innerQuery, parentDocMapper.typeFilter());
        return new HasChildQueryBuilder.LateParsingQuery(childrenQuery,
                                                         innerQuery,
                                                         HasChildQueryBuilder.DEFAULT_MIN_CHILDREN,
                                                         HasChildQueryBuilder.DEFAULT_MAX_CHILDREN,
                                                         type,
                                                         score ? ScoreMode.Max : ScoreMode.None,
                                                         parentChildIndexFieldData,
                                                         context.getSearchSimilarity());
    }

    @Override
    protected void doXContent(XContentBuilder builder, Params params) throws IOException {
        builder.startObject(NAME);
        builder.field(HasParentQueryParser.QUERY_FIELD.getPreferredName());
        query.toXContent(builder, params);
        builder.field(HasParentQueryParser.TYPE_FIELD.getPreferredName(), type);
        builder.field(HasParentQueryParser.SCORE_FIELD.getPreferredName(), score);
        printBoostAndQueryName(builder);
        if (innerHit != null) {
            builder.field(HasParentQueryParser.INNER_HITS_FIELD.getPreferredName(), innerHit, params);
        }
        builder.endObject();
    }

    @Override
    public String getWriteableName() {
        return NAME;
    }

    protected HasParentQueryBuilder(StreamInput in) throws IOException {
        type = in.readString();
        score = in.readBoolean();
        query = in.readQuery();
        innerHit = InnerHitBuilder.optionalReadFromStream(in);
    }

    @Override
    protected HasParentQueryBuilder doReadFrom(StreamInput in) throws IOException {
        return new HasParentQueryBuilder(in);
    }

    @Override
    protected void doWriteTo(StreamOutput out) throws IOException {
        out.writeString(type);
        out.writeBoolean(score);
        out.writeQuery(query);
        if (innerHit != null) {
            out.writeBoolean(true);
            innerHit.writeTo(out);
        } else {
            out.writeBoolean(false);
        }
    }

    @Override
    protected boolean doEquals(HasParentQueryBuilder that) {
        return Objects.equals(query, that.query)
                && Objects.equals(type, that.type)
                && Objects.equals(score, that.score)
                && Objects.equals(innerHit, that.innerHit);
    }

    @Override
    protected int doHashCode() {
        return Objects.hash(query, type, score, innerHit);
    }

    @Override
    protected QueryBuilder<?> doRewrite(QueryRewriteContext queryShardContext) throws IOException {
        QueryBuilder rewrite = query.rewrite(queryShardContext);
        if (rewrite != query) {
            HasParentQueryBuilder hasParentQueryBuilder = new HasParentQueryBuilder(type, rewrite);
            hasParentQueryBuilder.score(score);
            hasParentQueryBuilder.innerHit(innerHit);
            return hasParentQueryBuilder;
        }
        return this;
    }
}
