/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.gyyun.ds.extract.base.client;

import com.gyyun.ds.extract.base.IRpcResponse;
import com.gyyun.ds.extract.base.RpcMethod;
import com.gyyun.ds.extract.base.StandardRpcRequest;
import com.gyyun.ds.extract.base.SyncRequestDto;
import com.gyyun.ds.extract.base.exception.MethodInvocationException;
import com.gyyun.ds.extract.base.protocal.Transporter;
import com.gyyun.ds.extract.base.protocal.TransporterHeader;
import com.gyyun.ds.extract.base.serialize.JsonSerializer;
import com.gyyun.ds.extract.base.utils.Host;

import java.lang.reflect.Method;

class SyncClientMethodInvoker extends AbstractClientMethodInvoker {

    SyncClientMethodInvoker(Host serverHost, Method localMethod, NettyRemotingClient nettyRemotingClient) {
        super(serverHost, localMethod, nettyRemotingClient);
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        RpcMethod sync = method.getAnnotation(RpcMethod.class);
        final Transporter transporter = Transporter.of(
                TransporterHeader.of(methodIdentifier),
                JsonSerializer.serialize(StandardRpcRequest.of(args)));

        final SyncRequestDto syncRequestDto = SyncRequestDto.builder()
                .timeoutMillis(sync.timeout())
                .retryStrategy(sync.retry())
                .transporter(transporter)
                .serverHost(serverHost)
                .build();
        IRpcResponse iRpcResponse = nettyRemotingClient.sendSync(syncRequestDto);
        if (!iRpcResponse.isSuccess()) {
            throw MethodInvocationException.of(iRpcResponse.getMessage());
        }
        if (iRpcResponse.getBody() == null) {
            return null;
        }
        Class<?> responseClass = method.getReturnType();
        return JsonSerializer.deserialize(iRpcResponse.getBody(), responseClass);
    }
}
