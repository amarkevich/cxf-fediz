/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.cxf.fediz.samlsso.service;

import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;

@Path("/services")
public class DoubleItService {

    @GET
    @Produces("application/xml")
    @Path("/{numberToDouble}/")
    @RolesAllowed({ "User", "Admin", "Manager" })
    public Number doubleIt(@PathParam("numberToDouble") int numberToDouble) {
        Number newNumber = new Number();
        newNumber.setDescription("This is the double number response");
        newNumber.setNumber(numberToDouble * 2);
        return newNumber;
    }

    @POST
    @Produces("application/xml")
    @Consumes("application/x-www-form-urlencoded")
    @Path("/{numberToDouble}/")
    @RolesAllowed({ "User", "Admin", "Manager" })
    public Number doubleItPost(@PathParam("numberToDouble") int numberToDouble) {
        return doubleIt(numberToDouble);
    }

}
