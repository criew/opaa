package io.opaa.llm.web;

import io.opaa.api.dto.TranscriptionVocabulary;
import io.opaa.auth.Caller;
import io.opaa.auth.CurrentUser;
import io.opaa.common.ConditionalOnFeature;
import io.opaa.common.Feature;
import io.opaa.llm.PersonalTranscriptionVocabularyService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The caller's own vocabulary list for dictation. Every call acts on the caller's list only; there
 * is no path to another person's. Exists only while the feature speech-input is switched on.
 */
@RestController
@RequestMapping("/api/v1/me/transcription-vocabulary")
@ConditionalOnFeature(Feature.SPEECH_INPUT)
public class TranscriptionVocabularyController {

  private final PersonalTranscriptionVocabularyService vocabularies;

  public TranscriptionVocabularyController(PersonalTranscriptionVocabularyService vocabularies) {
    this.vocabularies = vocabularies;
  }

  @GetMapping
  public TranscriptionVocabulary getMyTranscriptionVocabulary(@Caller CurrentUser caller) {
    return new TranscriptionVocabulary(
        vocabularies.vocabularyOf(caller.id(), caller.organizationId()));
  }

  @PutMapping
  public TranscriptionVocabulary updateMyTranscriptionVocabulary(
      @Valid @RequestBody TranscriptionVocabulary request, @Caller CurrentUser caller) {
    return new TranscriptionVocabulary(
        vocabularies.replace(caller.id(), caller.organizationId(), request.getVocabulary()));
  }
}
