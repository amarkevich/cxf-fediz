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

package org.apache.cxf.fediz.service.idp.rest;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import org.apache.cxf.fediz.service.idp.domain.TrustedIdp;
import org.springframework.security.access.prepost.PreAuthorize;

@Produces({ MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON })
@Consumes({ MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON })
@Path("trusted-idps")
public interface TrustedIdpService {

    @GET
    @PreAuthorize("hasRole('TRUSTEDIDP_LIST')")
    TrustedIdps getTrustedIDPs(@QueryParam("start") int start,
                               @QueryParam("size") @DefaultValue("2") int size,
                               @Context UriInfo uriInfo);

    @GET
    @Path("{realm}")
    @PreAuthorize("hasRole('TRUSTEDIDP_READ')")
    TrustedIdp getTrustedIDP(@PathParam("realm") String realm);

    @POST
    @PreAuthorize("hasRole('TRUSTEDIDP_CREATE')")
    Response addTrustedIDP(@Context UriInfo ui, TrustedIdp trustedIdp);

    @PUT
    @Path("{realm}")
    @PreAuthorize("hasRole('TRUSTEDIDP_UPDATE')")
    Response updateTrustedIDP(@Context UriInfo ui, @PathParam("realm") String realm, TrustedIdp trustedIdp);

    @DELETE
    @Path("{realm}")
    @PreAuthorize("hasRole('TRUSTEDIDP_DELETE')")
    Response deleteTrustedIDP(@PathParam("realm") String realm);

}
