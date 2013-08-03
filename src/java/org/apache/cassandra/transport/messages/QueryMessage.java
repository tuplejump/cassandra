/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.cassandra.transport.messages;

import java.lang.reflect.Method;
import java.util.UUID;

import com.google.common.collect.ImmutableMap;
import org.apache.commons.lang.StringUtils;
import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.buffer.ChannelBuffers;

import org.apache.cassandra.cql3.QueryProcessor;
import org.apache.cassandra.db.ConsistencyLevel;
import org.apache.cassandra.exceptions.*;
import org.apache.cassandra.service.QueryState;
import org.apache.cassandra.tracing.Tracing;
import org.apache.cassandra.transport.*;
import org.apache.cassandra.utils.UUIDGen;

/**
 * A CQL query
 */
public class QueryMessage extends Message.Request
{
    static Object xQueryProcessor;
    static Class xQueryProcessorClass;
    static boolean xQueryProcessorInited;

    static {
        try {
            xQueryProcessorClass = Class.forName("com.tuplejump.stargate.cas.engine.XQueryProcessor");
            xQueryProcessor = xQueryProcessorClass.newInstance();
            xQueryProcessorInited = true;
        } catch (Exception e) {
            logger.error("com.tuplejump.stargate.cas.engine.XQueryProcessor could not be initialized", e);
            xQueryProcessorInited = false;
        }
    }

    public static final Message.Codec<QueryMessage> codec = new Message.Codec<QueryMessage>()
    {
        public QueryMessage decode(ChannelBuffer body)
        {
            String query = CBUtil.readLongString(body);
            ConsistencyLevel consistency = CBUtil.readConsistencyLevel(body);
            return new QueryMessage(query, consistency);
        }

        public ChannelBuffer encode(QueryMessage msg)
        {

            return ChannelBuffers.wrappedBuffer(CBUtil.longStringToCB(msg.query), CBUtil.consistencyLevelToCB(msg.consistency));
        }
    };

    public final String query;
    public final ConsistencyLevel consistency;

    public QueryMessage(String query, ConsistencyLevel consistency)
    {
        super(Message.Type.QUERY);
        this.query = query;
        this.consistency = consistency;
    }

    public ChannelBuffer encode()
    {
        return codec.encode(this);
    }

    public Message.Response execute(QueryState state)
    {
        boolean xcql = false;
        if (StringUtils.containsIgnoreCase(query, "XSELECT") || StringUtils.containsIgnoreCase(query, "XCREATE")) {
            xcql = true;
        }
        try
        {
            UUID tracingId = null;
            if (isTracingRequested())
            {
                tracingId = UUIDGen.getTimeUUID();
                state.prepareTracingSession(tracingId);
            }

            if (state.traceNextQuery())
            {
                state.createTracingSession();
                if(xcql){
                    Tracing.instance().begin("Execute XCQL query", ImmutableMap.of("query", query));
                }else{
                    Tracing.instance().begin("Execute CQL3 query", ImmutableMap.of("query", query));
                }
            }

            Message.Response response;
            if (xcql) {
                if (!xQueryProcessorInited)
                    throw new InvalidRequestException("com.tuplejump.stargate.cas.engine.XQueryProcessor was not initliazed properly");
                else {
                    try {
                        Method processM = xQueryProcessorClass.getMethod("processXQuery", String.class, ConsistencyLevel.class, QueryState.class);
                        response = (Response) processM.invoke(null, query, consistency, state);
                    } catch (java.lang.reflect.InvocationTargetException e) {
                        logger.error("Error occured in executing xquery", e.getTargetException());
                        throw new InvalidRequestException(e.getTargetException().getMessage());
                    }
                }
            } else {
                response = QueryProcessor.process(query, consistency, state);
            }

            if (tracingId != null)
                response.setTracingId(tracingId);

            return response;
        }
        catch (Exception e)
        {
            if (!((e instanceof RequestValidationException) || (e instanceof RequestExecutionException)))
                logger.error("Unexpected error during query", e);
            return ErrorMessage.fromException(e);
        }
        finally
        {
            Tracing.instance().stopSession();
        }
    }

    @Override
    public String toString()
    {
        return "QUERY " + query;
    }
}
