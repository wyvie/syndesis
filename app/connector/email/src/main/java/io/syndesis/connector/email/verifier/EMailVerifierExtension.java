/*
 * Copyright (C) 2016 Red Hat, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.syndesis.connector.email.verifier;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Map;
import javax.mail.Session;
import javax.mail.Store;
import javax.mail.Transport;
import org.apache.camel.CamelContext;
import org.apache.camel.component.extension.verifier.DefaultComponentVerifierExtension;
import org.apache.camel.component.extension.verifier.ResultBuilder;
import org.apache.camel.component.extension.verifier.ResultErrorBuilder;
import org.apache.camel.component.extension.verifier.ResultErrorHelper;
import org.apache.camel.component.mail.JavaMailSender;
import org.apache.camel.component.mail.MailConfiguration;
import org.apache.camel.util.ObjectHelper;
import org.apache.camel.util.jsse.SSLContextParameters;
import io.syndesis.connector.email.EMailConstants;
import io.syndesis.connector.email.EMailUtil;

public class EMailVerifierExtension extends DefaultComponentVerifierExtension implements EMailConstants {

    protected EMailVerifierExtension(String defaultScheme, CamelContext context) {
        super(defaultScheme, context);
    }


    // *********************************
    // Parameters validation
    // *********************************

    @Override
    protected Result verifyParameters(Map<String, Object> parameters) {

        ResultBuilder builder = ResultBuilder.withStatusAndScope(Result.Status.OK, Scope.PARAMETERS)
            .error(ResultErrorHelper.requiresOption(PROTOCOL, parameters))
            .error(ResultErrorHelper.requiresOption(HOST, parameters))
            .error(ResultErrorHelper.requiresOption(PORT, parameters))
            .error(ResultErrorHelper.requiresOption(USER, parameters))
            .error(ResultErrorHelper.requiresOption(PASSWORD, parameters));

        return builder.build();
    }

    // *********************************
    // Connectivity validation
    // *********************************

    @Override
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    protected Result verifyConnectivity(Map<String, Object> parameters) {
        ResultBuilder builder = ResultBuilder.withStatusAndScope(Result.Status.OK, Scope.CONNECTIVITY);

        try {
            secureProtocol(parameters);

            //
            // setProperties will strip parameters key/values so if anything else wants to read
            // them then they must be read first.
            //
            SSLContextParameters sslContextParameters = EMailUtil.createSSLContextParameters(parameters);
            parameters.put(SSL_CONTEXT_PARAMETERS, sslContextParameters);
            MailConfiguration configuration = setProperties(new MailConfiguration(), parameters);

            String starttls = (String) parameters.get(STARTTLS);
            if (ObjectHelper.isNotEmpty(starttls)) {
                configuration.getAdditionalJavaMailProperties()
                        .setProperty("mail.smtp.starttls.enable", "true");
                configuration.getAdditionalJavaMailProperties()
                        .setProperty("mail.smtp.starttls.required", "true");
            }

            JavaMailSender sender = createJavaMailSender(configuration);
            Session session = sender.getSession();

            Protocols protocol = Protocols.getValueOf(configuration.getProtocol());
            if (protocol.isReceiver()) {
                Store store = session.getStore(configuration.getProtocol());
                try {
                    store.connect(configuration.getHost(), configuration.getPort(),
                                      configuration.getUsername(), configuration.getPassword());
                } finally {
                    if (store.isConnected()) {
                        store.close();
                    }
                }
            } else if (protocol.isProducer()) {
                Transport transport = session.getTransport(protocol.id());
                try {
                    transport.connect(configuration.getHost(), configuration.getPort(),
                                  configuration.getUsername(), configuration.getPassword());
                } finally {
                    if (transport.isConnected()) {
                        transport.close();
                    }
                }
            }
        } catch (Exception e) {
            ResultErrorBuilder errorBuilder = ResultErrorBuilder.withCodeAndDescription(VerificationError.StandardCode.AUTHENTICATION, e.getMessage())
                .detail("mail_exception_message", e.getMessage()).detail(VerificationError.ExceptionAttribute.EXCEPTION_CLASS, e.getClass().getName())
                .detail(VerificationError.ExceptionAttribute.EXCEPTION_INSTANCE, e);

            builder.error(errorBuilder.build());
        }

        return builder.build();
    }


    private void secureProtocol(Map<String, Object> parameters) {
        String protocol = (String) parameters.get(PROTOCOL);
        if (ObjectHelper.isEmpty(protocol)) {
            return;
        }

        String secure = (String) parameters.get(SECURE);
        if (ObjectHelper.isEmpty(secure) || protocol.endsWith("s")) {
            return;
        }

        parameters.put(PROTOCOL, protocol + "s");
    }


    private JavaMailSender createJavaMailSender(MailConfiguration configuration)
        throws NoSuchMethodException, SecurityException, IllegalAccessException, IllegalArgumentException, InvocationTargetException {
        Method method = MailConfiguration.class.getDeclaredMethod("createJavaMailSender");
        method.setAccessible(true);
        return (JavaMailSender) method.invoke(configuration);
    }
}
