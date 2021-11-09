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
package org.apache.cxf.fediz.service.idp.beans.samlsso;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Base64;
import java.util.Date;
import java.util.HashSet;
import java.util.Set;

import org.w3c.dom.Document;

import org.apache.cxf.common.util.Base64Utility;
import org.apache.cxf.fediz.core.exception.ProcessingException;
import org.apache.cxf.fediz.core.exception.ProcessingException.TYPE;
import org.apache.cxf.fediz.core.util.CertsUtils;
import org.apache.cxf.fediz.service.idp.IdpConstants;
import org.apache.cxf.fediz.service.idp.domain.Application;
import org.apache.cxf.fediz.service.idp.domain.Idp;
import org.apache.cxf.fediz.service.idp.samlsso.SAMLAbstractRequest;
import org.apache.cxf.fediz.service.idp.samlsso.SAMLAuthnRequest;
import org.apache.cxf.fediz.service.idp.samlsso.SAMLLogoutRequest;
import org.apache.cxf.fediz.service.idp.util.WebUtils;
import org.apache.cxf.rs.security.saml.DeflateEncoderDecoder;
import org.apache.cxf.rs.security.saml.sso.SSOConstants;
import org.apache.cxf.staxutils.StaxUtils;
import org.apache.wss4j.common.crypto.CertificateStore;
import org.apache.wss4j.common.crypto.Crypto;
import org.apache.wss4j.common.ext.WSSecurityException;
import org.apache.wss4j.common.saml.OpenSAMLUtil;
import org.apache.wss4j.common.saml.SAMLKeyInfo;
import org.apache.wss4j.common.saml.SAMLUtil;
import org.apache.wss4j.common.util.DOM2Writer;
import org.apache.wss4j.dom.WSDocInfo;
import org.apache.wss4j.dom.engine.WSSConfig;
import org.apache.wss4j.dom.handler.RequestData;
import org.apache.wss4j.dom.saml.WSSSAMLKeyInfoProcessor;
import org.apache.wss4j.dom.validate.Credential;
import org.apache.wss4j.dom.validate.SignatureTrustValidator;
import org.apache.wss4j.dom.validate.Validator;
import org.apache.xml.security.algorithms.JCEMapper;
import org.opensaml.saml.saml2.core.AuthnRequest;
import org.opensaml.saml.saml2.core.LogoutRequest;
import org.opensaml.saml.saml2.core.RequestAbstractType;
import org.opensaml.saml.security.impl.SAMLSignatureProfileValidator;
import org.opensaml.security.credential.BasicCredential;
import org.opensaml.security.x509.BasicX509Credential;
import org.opensaml.xmlsec.signature.KeyInfo;
import org.opensaml.xmlsec.signature.Signature;
import org.opensaml.xmlsec.signature.support.SignatureException;
import org.opensaml.xmlsec.signature.support.SignatureValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.webflow.execution.RequestContext;

/**
 * Parse the received SAMLRequest into an OpenSAML AuthnRequest or LogoutRequest
 */
@Component
public class AuthnRequestParser {

    private static final Logger LOG = LoggerFactory.getLogger(AuthnRequestParser.class);
    private static final String RSA_SHA256 = "http://www.w3.org/2001/04/xmldsig-more#rsa-sha256";
    private static final String RSA_SHA384 = "http://www.w3.org/2001/04/xmldsig-more#rsa-sha384";
    private static final String RSA_SHA512 = "http://www.w3.org/2001/04/xmldsig-more#rsa-sha512";
    private static final String RSA_SHA1_MGF1 = "http://www.w3.org/2007/05/xmldsig-more#sha1-rsa-MGF1";
    private static final String RSA_SHA256_MGF1 = "http://www.w3.org/2007/05/xmldsig-more#sha256-rsa-MGF1";
    private static final String DSA_SHA256 = "http://www.w3.org/2009/xmldsig11#dsa-sha256";
    private static final Set<String> SIG_ALGS = new HashSet<>(Arrays.asList(
        SSOConstants.RSA_SHA1,
        SSOConstants.DSA_SHA1,
        RSA_SHA256,
        RSA_SHA384,
        RSA_SHA512,
        RSA_SHA1_MGF1,
        RSA_SHA256_MGF1,
        DSA_SHA256));

    private boolean supportDeflateEncoding;
    private boolean requireSignature = true;

    public void parseSAMLRequest(RequestContext context, Idp idp, String samlRequest,
                                 String sigAlg, String signature, String relayState) throws ProcessingException {
        LOG.debug("Received SAML Request: {}", samlRequest);

        if (samlRequest == null) {
            WebUtils.removeAttribute(context, IdpConstants.SAML_AUTHN_REQUEST);
            throw new ProcessingException(TYPE.BAD_REQUEST);
        } else {
            final RequestAbstractType parsedRequest;
            try {
                parsedRequest = extractRequest(context, samlRequest);
            } catch (Exception ex) {
                LOG.warn("Error parsing request: {}", ex.getMessage());
                throw new ProcessingException(TYPE.BAD_REQUEST);
            }

            // Store various attributes from the AuthnRequest/LogoutRequest
            if (parsedRequest instanceof AuthnRequest) {
                SAMLAuthnRequest authnRequest = new SAMLAuthnRequest((AuthnRequest)parsedRequest);
                WebUtils.putAttributeInFlowScope(context, IdpConstants.SAML_AUTHN_REQUEST, authnRequest);
            } else if (parsedRequest instanceof LogoutRequest) {
                SAMLLogoutRequest logoutRequest = new SAMLLogoutRequest((LogoutRequest)parsedRequest);
                WebUtils.putAttributeInFlowScope(context, IdpConstants.SAML_LOGOUT_REQUEST, logoutRequest);
                if (logoutRequest.getNotOnOrAfter() != null && (new Date()).after(logoutRequest.getNotOnOrAfter())) {
                    LOG.debug("The LogoutRequest is expired");
                    throw new ProcessingException(TYPE.BAD_REQUEST);
                }
            }

            validateRequest(parsedRequest);

            // Check the signature
            try {
                if (parsedRequest.isSigned()) {
                    // Check destination
                    checkDestination(context, parsedRequest);

                    // Check signature
                    X509Certificate validatingCert =
                        getValidatingCertificate(idp, parsedRequest.getIssuer().getValue());
                    Crypto issuerCrypto = new CertificateStore(new X509Certificate[] {validatingCert});
                    validateRequestSignature(parsedRequest.getSignature(), issuerCrypto);
                } else if (signature != null) {
                    // Check destination
                    checkDestination(context, parsedRequest);

                    // Check signature
                    validateSeparateSignature(idp, sigAlg, signature, relayState,
                              samlRequest, parsedRequest.getIssuer().getValue());
                } else if (requireSignature) {
                    LOG.debug("No signature is present, therefore the request is rejected");
                    throw new ProcessingException(TYPE.BAD_REQUEST);
                } else {
                    LOG.debug("No signature is present, but this is allowed by configuration");
                }
            } catch (Exception ex) {
                LOG.debug("Error validating SAML Signature", ex);
                throw new ProcessingException(TYPE.BAD_REQUEST);
            }

            LOG.debug("SAML Request with id '{}' successfully parsed", parsedRequest.getID());
        }
    }

    public String retrieveRealm(RequestContext context) {
        SAMLAbstractRequest request =
            (SAMLAbstractRequest)WebUtils.getAttributeFromFlowScope(context, IdpConstants.SAML_AUTHN_REQUEST);
        if (request == null) {
            request = (SAMLAbstractRequest)WebUtils.getAttributeFromFlowScope(context,
                                                                              IdpConstants.SAML_LOGOUT_REQUEST);
        }

        if (request != null) {
            String issuer = request.getIssuer();
            LOG.debug("Parsed SAML Request Issuer: {}", issuer);
            return issuer;
        }

        LOG.debug("No AuthnRequest or LogoutRequest available to be parsed");
        return null;
    }

    public String retrieveConsumerURL(RequestContext context) {
        // If it's a LogoutRequest we just want to get the logout endpoint from the configuration
        SAMLLogoutRequest logoutRequest =
            (SAMLLogoutRequest)WebUtils.getAttributeFromFlowScope(context, IdpConstants.SAML_LOGOUT_REQUEST);
        if (logoutRequest != null) {
            Idp idpConfig = (Idp) WebUtils.getAttributeFromFlowScope(context, "idpConfig");
            String realm = retrieveRealm(context);
            Application serviceConfig = idpConfig.findApplication(realm);
            if (serviceConfig != null) {
                String logoutEndpoint = serviceConfig.getLogoutEndpoint();
                if (logoutEndpoint != null) {
                    LOG.debug("Attempting to use the configured logout endpoint: {}", logoutEndpoint);
                    return logoutEndpoint;
                }
            }
            LOG.debug("No LogoutEndpoint has been configured for this application");
            return "/";
        }

        SAMLAuthnRequest authnRequest =
            (SAMLAuthnRequest)WebUtils.getAttributeFromFlowScope(context, IdpConstants.SAML_AUTHN_REQUEST);

        if (authnRequest != null && authnRequest.getConsumerServiceURL() != null) {
            String consumerURL = authnRequest.getConsumerServiceURL();
            LOG.debug("Parsed SAML AuthnRequest Consumer URL: {}", consumerURL);
            return consumerURL;
        }

        LOG.debug("No AuthnRequest available to be parsed");

        Idp idpConfig = (Idp) WebUtils.getAttributeFromFlowScope(context, "idpConfig");
        String realm = retrieveRealm(context);
        Application serviceConfig = idpConfig.findApplication(realm);
        if (serviceConfig != null) {
            String racs = serviceConfig.getPassiveRequestorEndpoint();
            LOG.debug("Attempting to use the configured passive requestor endpoint instead: {}", racs);
            return racs;
        }

        return null;
    }

    public String retrieveRequestId(RequestContext context) {
        SAMLAbstractRequest request =
            (SAMLAbstractRequest)WebUtils.getAttributeFromFlowScope(context, IdpConstants.SAML_AUTHN_REQUEST);
        if (request == null) {
            request = (SAMLAbstractRequest)WebUtils.getAttributeFromFlowScope(context,
                                                                              IdpConstants.SAML_LOGOUT_REQUEST);
        }

        if (request != null && request.getRequestId() != null) {
            String id = request.getRequestId();
            LOG.debug("Parsed SAML Request Id: {}", id);
            return id;
        }

        LOG.debug("No AuthnRequest/LogoutRequest available to be parsed");
        return null;
    }

    public String retrieveRequestIssuer(RequestContext context) {
        SAMLAbstractRequest request =
            (SAMLAbstractRequest)WebUtils.getAttributeFromFlowScope(context, IdpConstants.SAML_AUTHN_REQUEST);
        if (request == null) {
            request = (SAMLAbstractRequest)WebUtils.getAttributeFromFlowScope(context,
                                                                              IdpConstants.SAML_LOGOUT_REQUEST);
        }

        if (request != null && request.getIssuer() != null) {
            String issuer = request.getIssuer();
            LOG.debug("Parsed SAML Request Issuer: {}", issuer);
            return issuer;
        }

        LOG.debug("No AuthnRequest/LogoutRequest available to be parsed");
        return null;
    }

    public boolean isForceAuthentication(RequestContext context) {
        SAMLAuthnRequest authnRequest =
            (SAMLAuthnRequest)WebUtils.getAttributeFromFlowScope(context, IdpConstants.SAML_AUTHN_REQUEST);
        if (authnRequest != null) {
            return authnRequest.isForceAuthn();
        }

        LOG.debug("No AuthnRequest available to be parsed");
        return false;
    }

    protected RequestAbstractType extractRequest(RequestContext context, String samlRequest) throws Exception {
        byte[] deflatedToken = Base64Utility.decode(samlRequest);
        String httpMethod = WebUtils.getHttpServletRequest(context).getMethod();

        InputStream tokenStream = supportDeflateEncoding || "GET".equals(httpMethod)
             ? new DeflateEncoderDecoder().inflateToken(deflatedToken)
                 : new ByteArrayInputStream(deflatedToken);

        Document responseDoc = StaxUtils.read(new InputStreamReader(tokenStream, StandardCharsets.UTF_8));
        if (LOG.isDebugEnabled()) {
            LOG.debug(DOM2Writer.nodeToString(responseDoc));
        }
        return (RequestAbstractType)OpenSAMLUtil.fromDom(responseDoc.getDocumentElement());
    }

    public boolean isSupportDeflateEncoding() {
        return supportDeflateEncoding;
    }

    public void setSupportDeflateEncoding(boolean supportDeflateEncoding) {
        this.supportDeflateEncoding = supportDeflateEncoding;
    }

    private void validateRequest(RequestAbstractType parsedRequest) throws ProcessingException {
        if (parsedRequest.getIssuer() == null) {
            LOG.debug("No Issuer is present in the AuthnRequest/LogoutRequest");
            throw new ProcessingException(TYPE.BAD_REQUEST);
        }

        String format = parsedRequest.getIssuer().getFormat();
        if (format != null
            && !"urn:oasis:names:tc:SAML:2.0:nameid-format:entity".equals(format)) {
            LOG.debug("An invalid Format attribute was received: {}", format);
            throw new ProcessingException(TYPE.BAD_REQUEST);
        }

        if (parsedRequest instanceof AuthnRequest) {
            // No SubjectConfirmation Elements are allowed
            AuthnRequest authnRequest = (AuthnRequest)parsedRequest;
            if (authnRequest.getSubject() != null
                && authnRequest.getSubject().getSubjectConfirmations() != null
                && !authnRequest.getSubject().getSubjectConfirmations().isEmpty()) {
                LOG.debug("An invalid SubjectConfirmation Element was received");
                throw new ProcessingException(TYPE.BAD_REQUEST);
            }
        }
    }

    private void validateSeparateSignature(Idp idp, String sigAlg, String signature, String relayState,
                                           String samlRequest, String realm) throws Exception {
        // Check signature
        X509Certificate validatingCert = getValidatingCertificate(idp, realm);

        // Process the received SigAlg parameter - fall back to RSA SHA1
        final String processedSigAlg;
        if (sigAlg != null && SIG_ALGS.contains(sigAlg)) {
            processedSigAlg = sigAlg;
        } else {
            LOG.debug("Supplied SigAlg parameter is either null or not known, so falling back to use RSA-SHA1");
            processedSigAlg = SSOConstants.RSA_SHA1;
        }

        java.security.Signature sig =
            java.security.Signature.getInstance(JCEMapper.translateURItoJCEID(processedSigAlg));
        sig.initVerify(validatingCert);

        // Recreate request to sign
        String requestToSign =
                SSOConstants.SAML_REQUEST + '=' + URLEncoder.encode(samlRequest, StandardCharsets.UTF_8.name())
                + '&' + SSOConstants.RELAY_STATE + '=' + URLEncoder.encode(relayState, StandardCharsets.UTF_8.name())
                + '&' + SSOConstants.SIG_ALG + '=' + URLEncoder.encode(processedSigAlg, StandardCharsets.UTF_8.name());

        sig.update(requestToSign.getBytes(StandardCharsets.UTF_8));

        if (!sig.verify(Base64.getDecoder().decode(signature))) {
            LOG.debug("Signature validation failed");
            throw new ProcessingException(TYPE.BAD_REQUEST);
        }
    }

    private X509Certificate getValidatingCertificate(Idp idp, String realm)
        throws Exception {
        Application serviceConfig = idp.findApplication(realm);
        if (serviceConfig == null || serviceConfig.getValidatingCertificate() == null) {
            LOG.debug("No validating certificate found for realm {}", realm);
            throw new ProcessingException(TYPE.ISSUER_NOT_TRUSTED);
        }

        return CertsUtils.parseX509Certificate(serviceConfig.getValidatingCertificate());
    }

    private void checkDestination(RequestContext context, RequestAbstractType request) throws ProcessingException {
        // Check destination
        String destination = request.getDestination();
        LOG.debug("Validating destination: {}", destination);

        String localAddr = WebUtils.getHttpServletRequest(context).getRequestURL().toString();
        if (destination == null || !localAddr.startsWith(destination)) {
            LOG.debug("The destination {} does not match the local address {}", destination, localAddr);
            throw new ProcessingException(TYPE.BAD_REQUEST);
        }
    }

    /**
     * Validate the AuthnRequest or LogoutRequest signature
     */
    private void validateRequestSignature(
        Signature signature,
        Crypto sigCrypto
    ) throws WSSecurityException {
        RequestData requestData = new RequestData();
        requestData.setSigVerCrypto(sigCrypto);
        WSSConfig wssConfig = WSSConfig.getNewInstance();
        requestData.setWssConfig(wssConfig);
        // requestData.setCallbackHandler(callbackHandler);

        SAMLKeyInfo samlKeyInfo = null;

        KeyInfo keyInfo = signature.getKeyInfo();
        if (keyInfo != null) {
            try {
                Document doc = signature.getDOM().getOwnerDocument();
                requestData.setWsDocInfo(new WSDocInfo(doc));
                samlKeyInfo =
                    SAMLUtil.getCredentialFromKeyInfo(
                        keyInfo.getDOM(), new WSSSAMLKeyInfoProcessor(requestData), sigCrypto
                    );
            } catch (WSSecurityException ex) {
                LOG.debug("Error in getting KeyInfo from SAML AuthnRequest: {}", ex.getMessage(), ex);
                throw ex;
            }
        }

        if (samlKeyInfo == null) {
            LOG.debug("No KeyInfo supplied in the AuthnRequest signature");
            throw new WSSecurityException(WSSecurityException.ErrorCode.FAILURE, "invalidSAMLsecurity");
        }

        // Validate Signature against profiles
        validateSignatureAgainstProfiles(signature, samlKeyInfo);

        // Now verify trust on the signature
        Credential trustCredential = new Credential();
        trustCredential.setPublicKey(samlKeyInfo.getPublicKey());
        trustCredential.setCertificates(samlKeyInfo.getCerts());

        try {
            Validator signatureValidator = new SignatureTrustValidator();
            signatureValidator.validate(trustCredential, requestData);
        } catch (WSSecurityException e) {
            LOG.debug("Error in validating signature on SAML AuthnRequest: {}", e.getMessage(), e);
            throw new WSSecurityException(WSSecurityException.ErrorCode.FAILURE, "invalidSAMLsecurity");
        }
    }

    /**
     * Validate a signature against the profiles
     */
    private void validateSignatureAgainstProfiles(
        Signature signature,
        SAMLKeyInfo samlKeyInfo
    ) throws WSSecurityException {
        // Validate Signature against profiles
        SAMLSignatureProfileValidator validator = new SAMLSignatureProfileValidator();
        try {
            validator.validate(signature);
        } catch (SignatureException ex) {
            LOG.debug("Error in validating the SAML Signature: {}", ex.getMessage(), ex);
            throw new WSSecurityException(WSSecurityException.ErrorCode.FAILURE, "invalidSAMLsecurity");
        }

        final BasicCredential credential;
        if (samlKeyInfo.getCerts() != null) {
            credential = new BasicX509Credential(samlKeyInfo.getCerts()[0]);
        } else if (samlKeyInfo.getPublicKey() != null) {
            credential = new BasicCredential(samlKeyInfo.getPublicKey());
        } else {
            LOG.debug("Can't get X509Certificate or PublicKey to verify signature");
            throw new WSSecurityException(WSSecurityException.ErrorCode.FAILURE, "invalidSAMLsecurity");
        }
        try {
            SignatureValidator.validate(signature, credential);
        } catch (SignatureException ex) {
            LOG.debug("Error in validating the SAML Signature: {}", ex.getMessage(), ex);
            throw new WSSecurityException(WSSecurityException.ErrorCode.FAILURE, "invalidSAMLsecurity");
        }
    }

    public boolean isRequireSignature() {
        return requireSignature;
    }

    /**
     * Whether to require a signature or not on the AuthnRequest
     * @param requireSignature
     */
    public void setRequireSignature(boolean requireSignature) {
        this.requireSignature = requireSignature;
    }

}
