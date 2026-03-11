package com.skribbl.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

@Service
public class WordService {

    private List<String> words = new ArrayList<>();

    @PostConstruct
    public void loadWords() {
        try {
            ClassPathResource resource = new ClassPathResource("words.json");
            InputStream inputStream = resource.getInputStream();
            ObjectMapper mapper = new ObjectMapper();
            Map<String, List<String>> wordMap = mapper.readValue(
                    inputStream, new TypeReference<Map<String, List<String>>>() {}
            );
            for (List<String> categoryWords : wordMap.values()) {
                words.addAll(categoryWords);
            }
            // Make the base list unmodifiable after loading
            words = Collections.unmodifiableList(words);
        } catch (IOException e) {
            words = List.of(
                    "apple", "banana", "car", "dog", "elephant",
                    "fish", "guitar", "house", "ice cream", "jacket",
                    "kite", "lion", "mountain", "notebook", "ocean",
                    "piano", "queen", "rainbow", "sun", "tree",
                    "umbrella", "violin", "whale", "xylophone", "yoga",
                    "zebra", "airplane", "basketball", "camera", "diamond",
                    "fire", "globe", "hammer", "island", "jungle",
                    "knight", "lamp", "mirror", "ninja", "owl",
                    "penguin", "rocket", "snake", "tornado", "unicorn",
                    "volcano", "waterfall", "bridge", "castle", "dragon",
                    "feather", "garden", "helicopter", "igloo", "jellyfish",
                    "kangaroo", "lighthouse", "mushroom", "necklace", "octopus",
                    "parachute", "robot", "skateboard", "telescope", "treasure",
                    "butterfly", "cactus", "dolphin", "fireworks", "giraffe",
                    "snowflake", "starfish", "submarine", "suitcase", "sandwich"
            );
        }
    }

    public List<String> getRandomWords(int count) {
        // Create a mutable copy, shuffle with thread-safe random
        List<String> shuffled = new ArrayList<>(words);
        Collections.shuffle(shuffled, ThreadLocalRandom.current());
        return shuffled.subList(0, Math.min(count, shuffled.size()));
    }
}