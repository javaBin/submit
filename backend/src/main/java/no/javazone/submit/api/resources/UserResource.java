package no.javazone.submit.api.resources;

import no.javazone.submit.api.representations.EmailAddress;
import no.javazone.submit.api.representations.Token;
import no.javazone.submit.api.session.AuthenticatedUser;
import no.javazone.submit.config.TokenConfiguration;
import no.javazone.submit.services.AuthenticationService;
import no.javazone.submit.services.EmailService;
import no.javazone.submit.services.SubmissionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.ws.rs.DELETE;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Response;

import static javax.ws.rs.core.Response.Status.FORBIDDEN;

@Path("/api/users")
@Component
public class UserResource {

    private final AuthenticationService authenticationService;
    private final EmailService emailService;
    private SubmissionService submissionService;
    private TokenConfiguration tokenConfiguration;

    @Autowired
    public UserResource(AuthenticationService authenticationService, EmailService emailService,
                        SubmissionService submissionService, TokenConfiguration tokenConfiguration) {
        this.authenticationService = authenticationService;
        this.emailService = emailService;
        this.submissionService = submissionService;
        this.tokenConfiguration = tokenConfiguration;
    }

    @POST
    @Path("/authtoken")
    public Response sendAuthenticationEmail(@QueryParam("email") EmailAddress email) {
        Token token = authenticationService.createTokenForEmail(email);
        emailService.sendTokenToUser(email, token);
        return Response.ok().build();
    }

    @POST
    @Path("/authtokenwithdraft")
    public Response sendAuthenticationEmailAndCreateDraft(@QueryParam("email") EmailAddress email, @QueryParam("secret") String secret) {
        if (!tokenConfiguration.generateonbehalfofother.equals(secret)) {
            return Response.status(FORBIDDEN).build();
        }
        Token token = authenticationService.createTokenForEmail(email);
        submissionService.createNewDraft(new AuthenticatedUser(email));
        emailService.sendTokenToUserAndInformAboutNewlyCreatedDraft(email, token);
        return Response.ok().build();
    }

    @DELETE
    @Path("/authtoken")
    public void indicateLoginTokenRemovedFromBrowser(@QueryParam("token") Token token) {
        authenticationService.indicateLoginTokenRemovedFromBrowser(token);
    }

}
