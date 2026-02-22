package xyz.moodf.diary.services;

import com.querydsl.core.types.dsl.Expressions;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import xyz.moodf.diary.entities.RecMusic;
import xyz.moodf.diary.entities.Sentiment;
import xyz.moodf.diary.repositories.RecMusicRepository;
import xyz.moodf.diary.repositories.SentimentRepository;
import xyz.moodf.member.entities.Member;
import xyz.moodf.spotify.entities.Music;
import xyz.moodf.spotify.entities.QMusic;
import xyz.moodf.spotify.repositories.MusicRepository;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Lazy
@Service
@RequiredArgsConstructor
public class RecommendService {
    private static Map<String, List<String>> sentiments;
    private final JPAQueryFactory queryFactory;
    private final MusicRepository repository;
    private final RecMusicRepository recMusicRepository;
    private final SentimentRepository sentimentRepository;

    // 일기 감정과 음악 감정 매치
    static {
        sentiments = new HashMap<>();
        sentiments.put("분노", List.of("anger", "angry"));
        sentiments.put("슬픔", List.of("sadness"));
        sentiments.put("상처", List.of("thirst", "sadness"));
        sentiments.put("당황", List.of("confusion", "surprise"));
        sentiments.put("기쁨", List.of("Love", "love", "pink", "joy", "True", "interest"));
        sentiments.put("불안", List.of("fear"));
    }

    public static List<String> getMusicEmotion(String sentiment) {
        return sentiment == null ? List.of() : sentiments.get(sentiment);
    }

    public RecMusic process(String gid, String emotion) {
        RecMusic recMusic = recMusicRepository.findById(gid).orElse(null);
        if (recMusic != null) return recMusic;

        // 없으면 새로 만들기
        RecMusic newRecMusic = new RecMusic();

        newRecMusic.setGid(gid);

        List<Music> musicList = getContents(emotion);

        String musicSeqList = musicList.stream()
                .map(music -> String.valueOf(music.getSeq()))
                .collect(Collectors.joining(","));

        newRecMusic.setMusics(musicSeqList);

        recMusicRepository.saveAndFlush(newRecMusic);

        return newRecMusic;
    }

    public List<Music> recMusicToMusicList(RecMusic recMusic) {
        String seqString = recMusic.getMusics();

        List<Long> musicSeqList = Arrays.stream(seqString.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(Long::valueOf)
                .toList();

        List<Music> musicList = new ArrayList<>();
        for (Long seq : musicSeqList) {
            musicList.add(get(seq));
        }

        return musicList;
    }

    /**
     * 콘텐츠 추천
     *
     * @param emotion Music의 emotion
     * @param limit 가져올 콘텐츠 개수
     * @return
     */
    public List<Music> getContents(String emotion, int limit) {
        List<String> sentiments = getMusicEmotion(emotion);
        if (sentiments == null || sentiments.isEmpty())
            return List.of();

        QMusic music = QMusic.music;
        List<Music> items = queryFactory.selectFrom(music)
                .where(music.emotion.in(sentiments), music.youtubeUrl.isNotEmpty())
                .orderBy(Expressions.numberTemplate(Double.class, "function('rand')").asc())
                .limit(limit)
                .fetch();

        // 추가 정보 처리
        items.forEach(this::addInfo);

        return items;
    }

    public List<Music> getContents(String emotion) {
        return getContents(emotion, 10);
    }

    /**
     * 콘텐츠 하나 조회
     *
     * @param seq 콘텐츠 seq
     * @return 콘텐츠 데이터
     */
    public Music get(Long seq) {
        Music item = repository.findById(seq).orElse(null);
        if (item != null) {
            addInfo(item);  // 추가 정보 처리
        }

        return item;
    }

    /**
     * 감정별 추천곡 목록 조회
     * @param member
     * @param emotion
     * @return
     */
    public List<Music> getSongs(Member member, String emotion) {
        if (member == null || !StringUtils.hasText(emotion)) {
            return List.of();
        }

        List<Sentiment> sentiments = sentimentRepository.findByMember(member);
        List<Music> songs = new ArrayList<>();

        for (Sentiment sentiment : sentiments) {
            String text = sentiment.getSentiments();
            if (!StringUtils.hasText(text)) continue;

            String first = text.split(" ")[0];
            if (!emotion.equals(first)) continue;

            RecMusic recMusic = recMusicRepository.findById(sentiment.getGid()).orElse(null);
            if (recMusic == null) continue;

            songs.addAll(recMusicToMusicList(recMusic));
        }

        return songs;
    }

    /**
     * 추가 정보 처리
     *
     * @param item
     */
    private void addInfo(Music item) {

        String ytbUrl = item.getYoutubeUrl();

        if (StringUtils.hasText(ytbUrl)) {
            Pattern pattern = Pattern.compile("v=([^&]*)&?");
            Matcher matcher = pattern.matcher(ytbUrl);

            if (matcher.find()) {
                String ytbId = matcher.group(1);
                item.setYoutubeId(ytbId);
            }
        }
    }
}
