package searchengine.services.lemma;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.lucene.morphology.LuceneMorphology;
import org.apache.lucene.morphology.russian.RussianLuceneMorphology;
import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class LemmaService {
        private final LuceneMorphology morphology;

    public LemmaService() throws IOException {
        this.morphology = new RussianLuceneMorphology();
    }

    // Очистка HTML и извлечение текста
    public String extractText(String html) {
        return html.replaceAll("<[^>]*>", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    // Главный метод: текст → Map<лемма, частота на странице>
    public Map<String, Integer> getLemmas(String text) {
        String cleanText = extractText(text).toLowerCase();
        String[] words = cleanText.split("\\s+");

        Map<String, Integer> lemmaFreq = new HashMap<>();

        for (String word : words) {
            if (word.length() < 2 || !word.matches("[а-яёa-z]+")) continue;

            try {
                List<String> morphInfo = morphology.getMorphInfo(word);
                if (morphInfo.isEmpty()) continue;

                String info = morphInfo.get(0);
                if (isServicePartOfSpeech(info)) continue;

                List<String> normalForms = morphology.getNormalForms(word);
                if (normalForms.isEmpty()) continue;

                String lemma = normalForms.get(0);
                lemmaFreq.merge(lemma, 1, Integer::sum);

            } catch (Exception e) {
                // слово не распознано — пропускаем
            }
        }
        return lemmaFreq;
    }

    private boolean isServicePartOfSpeech(String morphInfo) {
        return morphInfo.contains("СОЮЗ") ||
                morphInfo.contains("ПРЕДЛ") ||
                morphInfo.contains("МЕЖД") ||
                morphInfo.contains("ЧАСТ");
    }
}
