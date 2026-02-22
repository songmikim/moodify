package xyz.moodf.diary.services;

import com.querydsl.core.BooleanBuilder;
import com.querydsl.core.Tuple;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import xyz.moodf.diary.constants.StatisticType;
import xyz.moodf.diary.entities.Diary;
import xyz.moodf.diary.entities.DiaryId;
import xyz.moodf.diary.entities.QDiary;
import xyz.moodf.diary.entities.QSentiment;
import xyz.moodf.diary.exceptions.DiaryNotFoundException;
import xyz.moodf.diary.repositories.DiaryRepository;
import xyz.moodf.global.codevalue.services.CodeValueService;
import xyz.moodf.global.file.entities.FileInfo;
import xyz.moodf.global.file.services.FileInfoService;
import xyz.moodf.member.entities.Member;
import xyz.moodf.member.libs.MemberUtil;

import java.time.LocalDate;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Lazy
@Service
@RequiredArgsConstructor
public class DiaryInfoService {

    private final DiaryRepository diaryRepository;
    private final JPAQueryFactory jpaQueryFactory;
    private final MemberUtil memberUtil;
    private final CodeValueService codeValueService;
    private final FileInfoService fileInfoService;

    /**
     * 현재 로그인된 회원이 특정 날짜에 작성한 일기가 있는가?
     *
     * @param date 날짜
     * @return 작성 여부
     */
    public boolean isWritten(LocalDate date) {
        return get(date) != null;
    }

    /**
     * 특정 회원의 특정 날짜 일기 조회
     * @param member 회원 정보
     * @param date 날짜
     * @return 일기
     */
    public Diary get(Member member, LocalDate date) {
        DiaryId diaryId = new DiaryId(member, date);
        Diary diary = diaryRepository.findById(diaryId)
                .orElseThrow(DiaryNotFoundException::new);

        // 추가 정보 처리
        addInfo(diary);

        return diary;
    }

    /**
     * 로그인한 회원의 특정 날짜 일기 조회
     *
     * @param date 날짜
     * @return 일기
     */
    public Diary get(LocalDate date) {
        Member member = memberUtil.getMember();

        return get(member, date);
    }

    public List<Diary> getList(Member member, LocalDate sDate, LocalDate eDate) {
        QDiary diary = QDiary.diary;

        BooleanBuilder andBuilder = new BooleanBuilder();
        andBuilder.and(diary.member.eq(member));

        // 일기 작성일 기간 조회
        if (sDate != null) {
            andBuilder.and(diary.createdAt.goe(sDate.atStartOfDay()));
        }

        if (eDate != null) {
            andBuilder.and(diary.createdAt.loe(eDate.atTime(23, 59, 59)));
        }

        List<Diary> items = jpaQueryFactory.selectFrom(diary)
                .leftJoin(diary.member)
                .fetchJoin()
                .where(andBuilder)
                .orderBy(diary.createdAt.asc())
                .fetch();

        // 추가 정보 러리
        items.forEach(this::addInfo);

        return items;
    }

    public List<Diary> getList(Member member, int year, int month) {
        LocalDate currMonth = LocalDate.of(year, month, 1);
        LocalDate prevMonth = currMonth.minusMonths(1L);
        LocalDate nextMonth = currMonth.plusMonths(2L).minusMonths(1L);

        return getList(member, prevMonth, nextMonth);
    }

    public List<Diary> getList(Member member) {
        LocalDate today = LocalDate.now();
        return getList(member, today.getYear(), today.getMonthValue());
    }

    public List<Diary> getList(int year, int month) {
        return getList(memberUtil.getMember(), year, month);
    }

    public List<Diary> getList() {
        return getList(memberUtil.getMember());
    }


    /**
     * 회원별 기간 감정 통계
     *  일별 통계 : DAILY - LocalDate : 2025-07-13
     *  주별 통계 : WEEKLY - LocalDate : 매주 월요일
     *  월별 통계 : MONTHLY - LocalDate : 매월의 1일
     * @param member
     * @param sDate
     * @param eDate
     * @return
     */
    public Map<LocalDate, Map<String, Integer>> getStatistics(Member member, LocalDate sDate, LocalDate eDate, StatisticType type) {
        BooleanBuilder andBuilder = new BooleanBuilder();
        QDiary diary = QDiary.diary;
        andBuilder.and(diary.member.eq(member));

        if (sDate != null) {
            andBuilder.and(diary.date.goe(sDate));
        }

        if (eDate != null) {
            andBuilder.and(diary.date.loe(eDate));
        }

        List<Tuple> items = jpaQueryFactory.select(diary.date, diary.sentiments).from(diary)
                .where(andBuilder)
                .fetch();

        Map<LocalDate, Map<String, Integer>> data = new HashMap<>();
        for (Tuple item : items) {
            LocalDate date = item.get(diary.date);
            LocalDate key = null;
            if (date == null) continue;

            switch(type) {
                case WEEKLY: // 주별
                    int yoil = date.getDayOfWeek().getValue();
                    // 월(1), 화(2), 수(3)
                    key = date.minusDays(yoil - 1); // 매 주의 월요일
                    break;
                case MONTHLY: // 월별
                    key = LocalDate.of(date.getYear(), date.getMonthValue(), 1);
                    break;
                default: // 일별
                    key = date;
            }

            Map<String, Integer> stat = data.getOrDefault(key, new HashMap<>());
            String text = item.get(diary.sentiments);
            if (!StringUtils.hasText(text)) continue;

            List<String> sentiments = Arrays.stream(text.split(","))
                    .map(s -> s.split(" ")[0]).toList();

            for (String sentiment : sentiments) {
                int cnt = stat.getOrDefault(sentiment, 0);
                stat.put(sentiment, ++cnt);
            }

            data.put(key, stat);
        }

        return data;
    }

    public Map<LocalDate, Map<String, Integer>> getStatistics(LocalDate sDate, LocalDate eDate, StatisticType type) {
        return getStatistics(memberUtil.getMember(), sDate, eDate, type);
    }

    public Map<LocalDate, Map<String, Integer>> getStatistics(LocalDate sDate,  StatisticType type) {
        return getStatistics(sDate, null, type);
    }

    public String getStrongestEmotionImg(String gid) {
        // gid로 Diary 찾기
        QDiary diary_ = QDiary.diary;
        Diary diary = jpaQueryFactory
                .selectFrom(diary_)
                .where(diary_.gid.eq(gid))
                .fetchOne();
        if (diary == null || !StringUtils.hasText(diary.getStrongest())) return "";

        // strongest 감정 코드로 이미지 GID 찾기
        String iconGid = codeValueService.get(diary.getStrongest(), String.class);
        FileInfo file = fileInfoService.get(iconGid);

        // 이미지 태그 생성
        if (file != null) {
            return String.format("<img src='%s' class='sent-icon' width='100' height='100'>", file.getFileUrl());
        }

        return "";
    }

    /**
     * 추가 정보 처리
     *
     * @param item
     */
    public void addInfo(Diary item) {
        String sentiments = item.getSentiments();
        if (StringUtils.hasText(sentiments)) {
            Map<String, Integer> statistics = new HashMap<>();

            for (String s : sentiments.split(",")) {
                String sentiment = s.trim();
                if (sentiment.isEmpty()) continue;
                int cnt = statistics.getOrDefault(sentiment, 0);
                statistics.put(sentiment, ++cnt);
            }

            List<String> ranking = statistics.entrySet().stream()
                    .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                    .map(Map.Entry::getKey)
                    .toList();

            item.setStrongest(ranking.getFirst());  // 가장 강한 감정
            item.setStatistics(statistics);
            item.setRanking(ranking);  // 순위별 감정 목록
        }
    }

    public Map<String, Long> getSentimentFrequencies(Member member, int year, int month) {

        LocalDate firstDate = LocalDate.of(year, month, 1);
        LocalDate lastDate = firstDate.withDayOfMonth(firstDate.lengthOfMonth());

        QDiary diary = QDiary.diary;

        // 지정된 기간 동안 해당 멤버가 작성한 일기들의 날짜 리스트
        List<LocalDate> diaryDateList = jpaQueryFactory
                .select(diary.date)
                .from(diary)
                .where(
                        diary.member.eq(member),
                        diary.date.between(firstDate, lastDate)
                )
                .fetch();

        // 각 날짜의 대표 감정 리스트
        List<String> sentList = new ArrayList<>();
        for (LocalDate diaryDate : diaryDateList) {
            sentList.add(getMostFrequentSentiment(member, diaryDate));
        }

        // 지정된 기간 동안의 대표 감정 빈도 수
        Map<String, Long> sentimentFrequencyMap = sentList.stream()
                .filter(Objects::nonNull)  // null 값 제외
                .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));

        return sentimentFrequencyMap;
    }

    public String getMostFrequentSentiment(Member member, LocalDate date) {
        DiaryId diaryId = new DiaryId(member, date);
        QDiary diary = QDiary.diary;
        QSentiment sentiment = QSentiment.sentiment;

        List<String> allSentiments = jpaQueryFactory
                .select(sentiment.sentiments)
                .from(diary)
                .join(sentiment).on(diary.gid.eq(sentiment.gid))
                .where(
                        diary.member.eq(diaryId.getMember())
                                .and(diary.date.eq(diaryId.getDate()))
                )
                .fetch();

        System.out.println("1: " + allSentiments);

        // 각 감정의 빈도 수 저장
        Map<String, Integer> frequencyMap = new HashMap<>();
        for (String line : allSentiments) {
            if (line == null || line.isBlank()) continue;

            // string을 [] 형태로 변환
            String[] split = line.split(",");
            for (String s : split) {
                String trimmed = s.trim();  // 공백 제거
                if (trimmed.isEmpty()) continue;
                frequencyMap.put(trimmed, frequencyMap.getOrDefault(trimmed, 0) + 1);
            }
        }

        System.out.println("2: " + frequencyMap);

        // 가장 많이 나온 감정
        return frequencyMap.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey) // 가장 많이 나온 감정 문자열 반환
                .orElse(""); // 감정이 하나도 없을 경우 빈 문자열 반환
    }


}
