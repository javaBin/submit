module Submission.Encoder exposing (encoder)

import Submission.Model exposing (..)
import Json.Encode exposing (Value, object, string, list)


encoder : Submission -> Value
encoder submission =
    object
        [ ( "status", string submission.status )
        , ( "title", string submission.title )
        , ( "abstract", string submission.abstract )
        , ( "intendedAudience", string submission.intendedAudience )
        , ( "format", string submission.format )
        , ( "language", string submission.language )
        , ( "outline", string submission.outline )
        , ( "speakers", list <| List.map encodeSpeaker submission.speakers )
        ]


encodeSpeaker : Speaker -> Value
encodeSpeaker speaker =
    object
        [ ( "id", string speaker.id )
        , ( "name", string speaker.name )
        , ( "email", string speaker.email )
        , ( "bio", string speaker.bio )
        ]