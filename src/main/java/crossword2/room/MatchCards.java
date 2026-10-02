package crossword2.room;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import crossword2.puzzle.PuzzleDtos.WordCard;
import crossword2.room.RoomDtos.EntryCard;
import crossword2.word.Word;
import crossword2.word.WordRepository;

/** 끝난 경기의 단어 카드를 만든다. 경기가 끝난 뒤 참가자에게만 보여주는 경로({@link RoomService#result})에서만 쓴다. */
@Component
class MatchCards {

	private final WordRepository words;

	MatchCards(WordRepository words) {
		this.words = words;
	}

	@Transactional(readOnly = true)
	List<EntryCard> cardsOf(MatchOutcome outcome) {
		Map<Long, Word> byId = new HashMap<>();
		words.findAllById(outcome.entries().stream().map(e -> e.wordId).toList()).forEach(w -> byId.put(w.getId(), w));
		return outcome.entries().stream().map(e -> {
			Word w = byId.get(e.wordId);
			return new EntryCard(e.entryId,
					new WordCard(w.getId(), w.getEnglish(), w.getKorean(), w.getPartOfSpeech(), w.getDefinition(), w.getExample()));
		}).toList();
	}
}
