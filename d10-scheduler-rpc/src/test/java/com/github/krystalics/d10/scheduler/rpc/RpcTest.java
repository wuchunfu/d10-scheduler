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

package com.github.krystalics.d10.scheduler.rpc;


import com.github.krystalics.d10.scheduler.rpc.client.IRpcClient;
import com.github.krystalics.d10.scheduler.rpc.client.RpcClient;
import com.github.krystalics.d10.scheduler.rpc.config.NettyServerConfig;
import com.github.krystalics.d10.scheduler.rpc.remote.NettyClient;
import com.github.krystalics.d10.scheduler.rpc.remote.NettyServer;
import com.github.krystalics.d10.scheduler.rpc.utils.Host;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class RpcTest {
    private NettyServer nettyServer;

    private IUserService userService;

    private Host host;

    @Before
    public void before() throws Exception {
        nettyServer = new NettyServer(new NettyServerConfig());
        IRpcClient rpcClient = new RpcClient();
        host = new Host("127.0.0.1", 12346);
        userService = rpcClient.create(IUserService.class, host);
    }

    @Test
    public void sendTest() throws InterruptedException {
//        Integer result = userService.hi(3);
//        Assert.assertSame(4, result);
//        result = userService.hi(4);
//        Assert.assertSame(5, result);
//        //异步调用会第一时间返回一个空的结果，只有在远程调用返回 触发回调才会有收获
//        final String say = userService.say("async");
//        System.out.println("say first resp: " + say);
        final Boolean callBackIsFalse = userService.callBackIsFalse("async no call back");
//        System.out.println("callBackIsFalse first resp: " + callBackIsFalse);
//        userService.hi(999999);

//        userService.returnType("string");

        Thread.sleep(5000);
    }

    @After
    public void after() {
        NettyClient.getInstance().close();
        nettyServer.close();
    }

}
