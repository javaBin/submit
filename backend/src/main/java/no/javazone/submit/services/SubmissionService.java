package no.javazone.submit.services;

import no.javazone.submit.api.representations.*;
import no.javazone.submit.api.session.AuthenticatedUser;
import no.javazone.submit.config.ServerConfiguration;
import no.javazone.submit.config.SleepingPillConfiguration;
import no.javazone.submit.integrations.slack.SlackClient;
import no.javazone.submit.integrations.sleepingpill.SleepingPillClient;
import no.javazone.submit.integrations.sleepingpill.model.common.SessionComment;
import no.javazone.submit.integrations.sleepingpill.model.common.SessionStatus;
import no.javazone.submit.integrations.sleepingpill.model.create.CreatedSession;
import no.javazone.submit.integrations.sleepingpill.model.create.NewSession;
import no.javazone.submit.integrations.sleepingpill.model.get.Conferences;
import no.javazone.submit.integrations.sleepingpill.model.get.Session;
import no.javazone.submit.integrations.sleepingpill.model.get.Sessions;
import no.javazone.submit.integrations.sleepingpill.model.picture.CreatedPicture;
import no.javazone.submit.integrations.sleepingpill.model.update.UpdatedSession;
import no.javazone.submit.integrations.sleepingpill.model.update.UpdatedSpeaker;
import no.javazone.submit.util.AuditLogger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.ws.rs.ForbiddenException;
import javax.ws.rs.NotFoundException;
import java.util.*;

import static java.lang.String.format;
import static java.util.Collections.emptyList;
import static java.util.Optional.empty;
import static java.util.stream.Collectors.*;
import static no.javazone.submit.integrations.sleepingpill.model.common.SessionStatus.SUBMITTED;
import static no.javazone.submit.util.AuditLogger.Event.*;

@Service
public class SubmissionService {

    private final Logger LOG = LoggerFactory.getLogger(this.getClass());

    private final SleepingPillClient sleepingPill;

    private final Conferences conferences;
    private final EmailService emailService;
    private final SlackClient slackClient;

    private final SleepingPillConfiguration sleepingPillConfiguration;
    private final ServerConfiguration serverConfiguration;

    @Autowired
    public SubmissionService(SleepingPillClient sleepingPill, SleepingPillConfiguration sleepingPillConfiguration, ServerConfiguration serverConfiguration,
                             SlackClient slackClient, EmailService emailService) {
        this.sleepingPill = sleepingPill;
        this.sleepingPillConfiguration = sleepingPillConfiguration;
        this.serverConfiguration = serverConfiguration;
        this.slackClient = slackClient;
        this.emailService = emailService;

        // TODO (EHH): Refresh this periodically?
        conferences = sleepingPill.getConferences();
    }

    public SubmissionsForUser getSubmissionsForUser(AuthenticatedUser authenticatedUser) {
        Sessions sleepingPillSessions = sleepingPill.getTalksForSpeakerByEmail(authenticatedUser.emailAddress.toString());

        Map<String, List<Session>> groupedByConference = sleepingPillSessions.sessions.stream().collect(groupingBy(s -> s.conferenceId));

        AuditLogger.log(GET_ALL_TALKS, "user " + authenticatedUser.emailAddress);

        return new SubmissionsForUser(
                groupedByConference.entrySet().stream()
                        .map((e) -> sleepingpillYearToOurYear(e, authenticatedUser))
                        .sorted(Comparator.<Year, String>comparing(y -> y.year).reversed())
                        .collect(toList())
        );
    }

    private Year sleepingpillYearToOurYear(Map.Entry<String, List<Session>> e, AuthenticatedUser authenticatedUser) {
        return new Year(
                conferences.getNameFromId(e.getKey()),
                e.getValue().stream().map((session) -> fromSleepingPillSession(session, authenticatedUser)).collect(toList())
        );
    }

    public Submission getSubmissionForUser(AuthenticatedUser authenticatedUser, String submissionId) {
        Session session = sleepingPill.getSession(submissionId);
        checkSessionOwnership(session, authenticatedUser);
        AuditLogger.log(GET_SINGLE_TALK, "user " + authenticatedUser, "session " + submissionId);
        return fromSleepingPillSession(session, authenticatedUser);
    }

    public Submission createNewDraft(AuthenticatedUser authenticatedUser) {
        NewSession draft = NewSession.draft(authenticatedUser.emailAddress);
        String conferenceId = conferences.getIdFromSlug(sleepingPillConfiguration.activeYear);
        CreatedSession createdSession = sleepingPill.createSession(conferenceId, draft);
        AuditLogger.log(CREATE_DRAFT, "user " + authenticatedUser);
        return getSubmissionForUser(authenticatedUser, createdSession.id);
    }

    public Submission updateSubmission(AuthenticatedUser authenticatedUser, String submissionId, Submission submission, Optional<Comment> newComment) {
        submission.validate();

        Submission previousSubmission = getSubmissionForUser(authenticatedUser, submissionId);

        if (!isEditableBySubmitter(previousSubmission.conferenceId)) {
            LOG.warn(String.format("User %s tried to edit a previous year's submissions with is %s", authenticatedUser.emailAddress, submissionId));
            AuditLogger.log(EDIT_UNEDITABLE_TALK, "user " + authenticatedUser, "session " + submissionId);
            throw new ForbiddenException("Not allowed to edit previous year's submissions");
        }

        UpdatedSession updatedSession = new UpdatedSession(
                parseAndValidateStatus(authenticatedUser, previousSubmission, submission),
                submission.title,
                submission.theAbstract,
                submission.intendedAudience,
                submission.format,
                submission.language,
                submission.outline,
                submission.equipment,
                submission.length,
                submission.level,
                submission.suggestedKeywords,
                submission.infoToProgramCommittee,
                submission.speakers.stream().map(UpdatedSpeaker::fromApiObject).collect(toList())
        );
        newComment.ifPresent(comment -> {
            String speakerName = submission.speakers.stream()
                    .filter(s -> authenticatedUser.emailAddress.toString().equals(s.email))
                    .filter(s -> s.name != null && !s.name.isEmpty())
                    .findAny()
                    .map(s -> "Speaker: " + s.name)
                    .orElse("Speaker");
            SessionComment sessionComment = new SessionComment(speakerName, authenticatedUser.emailAddress, comment.comment);
            updatedSession.addComment(sessionComment);
        });
        sleepingPill.updateSession(submissionId, updatedSession);

        AuditLogger.log(UPDATE_TALK, "user " + authenticatedUser, "session " + submissionId);

        notifySlackAndEmailAndLog(authenticatedUser, submissionId, submission, previousSubmission, newComment);

        return getSubmissionForUser(authenticatedUser, submissionId);
    }

    private void notifySlackAndEmailAndLog(AuthenticatedUser authenticatedUser, String submissionId, Submission submission, Submission previousSubmission, Optional<Comment> newComment) {
        try {
            if (newComment.isPresent()) {
                slackClient.postTalkReceivedNewComment(
                        submissionId,
                        submission.title,
                        submission.speakers.stream().map(s -> s.name).collect(joining(" & ")),
                        previousSubmission.speakers.get(0).hasPicture ? previousSubmission.speakers.get(0).pictureUrl : null,
                        newComment.get()
                );
            } else if (SessionStatus.valueOf(previousSubmission.status) != SUBMITTED && SessionStatus.valueOf(submission.status) == SUBMITTED) {
                slackClient.postTalkMarkedForInReview(
                        submissionId,
                        submission.title,
                        submission.format,
                        submission.length,
                        submission.language,
                        submission.theAbstract,
                        submission.speakers.stream().map(s -> s.name).collect(joining(" & ")),
                        previousSubmission.speakers.get(0).hasPicture ? previousSubmission.speakers.get(0).pictureUrl : null
                );
                emailService.notifySpeakerAboutStatusChangeToInReview(submission);
                AuditLogger.log(CHANGE_STATUS_TO_SUBMITTED, "user " + authenticatedUser, "session " + submissionId);
            } else if (SessionStatus.valueOf(previousSubmission.status) == SUBMITTED && SessionStatus.valueOf(submission.status) != SUBMITTED) {
                slackClient.postTalkMarkedForNotInReview(
                        submissionId,
                        submission.title,
                        submission.speakers.get(0).name,
                        submission.speakers.get(0).email,
                        previousSubmission.speakers.get(0).hasPicture ? previousSubmission.speakers.get(0).pictureUrl : null
                );
                AuditLogger.log(CHANGE_STATUS_TO_DRAFT, "user " + authenticatedUser, "session " + submissionId);
            }
        } catch (Exception e) {
            LOG.warn("Some error happened when submitting status update to slack", e);
        }
    }

    private void checkSessionOwnership(Session session, AuthenticatedUser authenticatedUser) {
        boolean isSpeakerAtSession = session.speakers.stream().anyMatch(s -> s.email.equalsIgnoreCase(authenticatedUser.emailAddress.toString()));
        boolean isOriginalOwner = session.postedBy != null && session.postedBy.equalsIgnoreCase(authenticatedUser.emailAddress.toString());
        if (!isSpeakerAtSession && !isOriginalOwner) {
            LOG.warn(format("User %s tried to access session %s, which the user is not a speaker for or is not the original poster for...", authenticatedUser.emailAddress.toString(), session.sessionId));
            AuditLogger.log(ILLEGAL_TALK_ACCESS, "user " + authenticatedUser, "session " + session.sessionId);
            throw new NotFoundException("Session with ID " + session.sessionId + " not found");
        }
    }

    private SessionStatus parseAndValidateStatus(AuthenticatedUser authenticatedUser, Submission previousSubmission, Submission submission) {
        SessionStatus previousStatus = SessionStatus.valueOf(previousSubmission.status);
        SessionStatus status = SessionStatus.valueOf(submission.status);
        if (previousStatus == SessionStatus.HISTORIC) {
            LOG.warn(String.format("%s tried to set the status %s for a historic talk. This should not be editable. Bug???...", authenticatedUser.emailAddress.toString(), status.name()));
            AuditLogger.log(EDIT_UNEDITABLE_TALK, "user " + authenticatedUser, "session " + submission.id, "status " + status);
            throw new ForbiddenException("Tried to set a status that the user isn't allowed to set...");
        } else if (previousStatus == SessionStatus.APPROVED || previousStatus == SessionStatus.REJECTED) {
            // Don't edit status for approved or rejected talks, but allow editing
            return previousStatus;
        } else if (status == SessionStatus.DRAFT || status == SUBMITTED) {
            // Edit status for drafts and submitted talks
            return status;
        } else {
            LOG.warn(String.format("%s tried to set the status %s that the user isn't allowed to set...", authenticatedUser.emailAddress.toString(), status.name()));
            AuditLogger.log(EDIT_UNEDITABLE_TALK, "user " + authenticatedUser, "session " + submission.id, "status " + status);
            throw new ForbiddenException("Tried to set a status that the user isn't allowed to set...");
        }
    }

    private Submission fromSleepingPillSession(Session session, AuthenticatedUser authenticatedUser) {
        return new Submission(
                session.sessionId,
                session.conferenceId,
                session.status.name(),
                session.getTitle(),
                session.getAbstract(),
                session.getIntendedAudience(),
                session.getFormat(),
                session.getLanguage(),
                session.getOutline(),
                session.getEquipment(),
                session.getLength(),
                session.getLevel(),
                session.getSuggestedKeywords(),
                session.getInfoToProgramCommittee(),
                session.speakers.stream().map((speaker) -> fromSleepingPillSpeaker(session.sessionId, speaker, authenticatedUser)).collect(toList()),
                isEditableBySubmitter(session.conferenceId),
                session.comments != null ? session.comments.stream().map(comment -> new Comment(comment.from, comment.comment)).collect(toList()) : emptyList()
        );
    }

    private Speaker fromSleepingPillSpeaker(String sessionId, no.javazone.submit.integrations.sleepingpill.model.common.Speaker speaker, AuthenticatedUser authenticatedUser) {
        return new Speaker(
                speaker.id,
                speaker.name,
                speaker.email,
                speaker.getBio(),
                speaker.getZipCode(),
                speaker.getTwitter(),
                speaker.getPictureId(),
                speaker.getPictureId() != null,
                serverConfiguration.apiBaseUri + "/submissions/" + sessionId + "/speakers/" + speaker.id + "/picture",
                deleteble(speaker, authenticatedUser)
        );
    }

    private boolean deleteble(no.javazone.submit.integrations.sleepingpill.model.common.Speaker speaker, AuthenticatedUser authenticatedUser) {
        return !authenticatedUser.emailAddress.toString().equals(speaker.email);
    }

    private boolean isEditableBySubmitter(String sessionId) {
        return conferences.getSlugFromId(sessionId).equals(sleepingPillConfiguration.activeYear);
    }

    public UploadedPicture addPictureToSpeaker(AuthenticatedUser authenticatedUser, String submissionId, String speakerId, byte[] pictureStream, String mediaType) {
        Submission submission = getSubmissionForUser(authenticatedUser, submissionId);
        Optional<Speaker> speaker = submission.speakers.stream().filter(s -> s.id.equals(speakerId)).findAny();
        if (speaker.isPresent()) {
            CreatedPicture createdPicture = sleepingPill.uploadPicture(pictureStream, mediaType);
            speaker.get().setPictureId(createdPicture.id);
            AuditLogger.log(UPLOAD_SPEAKER_PICTURE, "user " + authenticatedUser, "session " + submissionId, "speaker " + speakerId);
            updateSubmission(authenticatedUser, submissionId, submission, empty());
            return new UploadedPicture(serverConfiguration.apiBaseUri + "/submissions/" + submissionId + "/speakers/" + speakerId + "/picture");
        } else {
            AuditLogger.log(ILLEGAL_SPEAKER_FOR_PICTURE_UPLOAD, "user " + authenticatedUser, "session " + submissionId, "speaker " + speakerId);
            throw new NotFoundException("Could not add picture. Did not find speaker with id " + speakerId + " on session " + submissionId);
        }
    }

    public byte[] getSpeakerPicture(String submissionId, String speakerId) {
        Session session = sleepingPill.getSession(submissionId);
        Optional<no.javazone.submit.integrations.sleepingpill.model.common.Speaker> speaker = session.speakers.stream().filter(s -> s.id.equals(speakerId)).findAny();
        if (speaker.isPresent()) {
            String pictureId = speaker.get().getPictureId();
            if (pictureId == null) {
                AuditLogger.log(GET_SPEAKER_IMAGE_MISSING_IMAGE, "session " + submissionId, "speaker " + speakerId);
                throw new NotFoundException("Didn't find stored picture for " + speakerId + " on session " + submissionId);
            } else {
                AuditLogger.log(GET_SPEAKER_PICTURE, "session " + submissionId, "speaker " + speakerId);
                return sleepingPill.getPicture(pictureId);
            }
        } else {
            AuditLogger.log(GET_SPEAKER_IMAGE_MISSING_SPEAKER, "session " + submissionId, "speaker " + speakerId);
            throw new NotFoundException("Didn't find speaker for speakerid " + speakerId + " on session " + submissionId);
        }
    }

    public Submission postComment(AuthenticatedUser authenticatedUser, String submissionId, Comment comment) {
        Submission existingSubmission = getSubmissionForUser(authenticatedUser, submissionId);
        updateSubmission(authenticatedUser, submissionId, existingSubmission, Optional.of(comment));
        AuditLogger.log(POST_COMMENT, "user " + authenticatedUser, "session " + submissionId, "comment " + comment.comment);
        return getSubmissionForUser(authenticatedUser, submissionId);
    }

}
