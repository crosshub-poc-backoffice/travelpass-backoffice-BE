package com.crosshub.backoffice.product.extract;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/** 결정적 검산: 같은 입력이면 늘 같은 판정. LLM 이 자주 틀리는 자릿수·연호·지어낸 숫자를 잡는지 */
class ExtractCheckTest {

    ObjectMapper om = new ObjectMapper();

    static final String DOC = """
            「博多港発、福岡空港着」フェリー太古＆ANAで行く!!上五島の島旅 3〜5日間
            販売期間 2026/4/1〜2027/2/28
            | 出発日 | 大人 | 小人 |
            | 4月 | 48,000円 | 32,000円 |
            | 5月 | 4.8万円 | 1万2,000円 |
            集合 22:30受付/23:45発
            取消料 旅行開始日の前日から起算してさかのぼって7日目にあたる日以降 20%
            """;

    ObjectNode draft(String json) throws Exception {
        return (ObjectNode) om.readTree(json);
    }

    /** 누락 검사(문서에 フェリー 가 있는데 교통 섹션 없음 등)를 뺀 문제들 — 다른 검사를 따로 보려고 */
    static java.util.List<String> facts(ObjectNode d) {
        return ExtractCheck.problems(d, DOC).stream().filter(p -> !p.startsWith("문서에 ")).toList();
    }

    @Test
    void 금액_표기를_엔_정수로() {
        assertThat(ExtractCheck.yen("48,000円")).isEqualTo(48000);
        assertThat(ExtractCheck.yen("¥48,000")).isEqualTo(48000);
        assertThat(ExtractCheck.yen("４８，０００円")).isEqualTo(48000); // 전각
        assertThat(ExtractCheck.yen("4.8万円")).isEqualTo(48000);
        assertThat(ExtractCheck.yen("1万2,000円")).isEqualTo(12000);
        assertThat(ExtractCheck.yen("お問い合わせ")).isNull();
    }

    @Test
    void 연호와_여러_날짜_표기() {
        assertThat(ExtractCheck.dates("令和8年4月1日〜令和9年2月28日"))
                .containsExactly(LocalDate.of(2026, 4, 1), LocalDate.of(2027, 2, 28));
        assertThat(ExtractCheck.dates("R8.4.1")).containsExactly(LocalDate.of(2026, 4, 1));
        assertThat(ExtractCheck.dates("2026/4/1〜2027/2/28")).hasSize(2);
        assertThat(ExtractCheck.dates("2026/13/1")).isEmpty();
    }

    @Test
    void 근거가_문서에_있어야_한다() {
        String doc = ExtractCheck.canon(DOC);
        assertThat(ExtractCheck.grounded("| 4月 | 48,000円 | 32,000円 |", doc)).isTrue();
        assertThat(ExtractCheck.grounded("4月 大人 50,000円", doc)).isFalse();
    }

    @Test
    void 자릿수_실수를_재계산으로_잡는다() throws Exception {
        ObjectNode d = draft("""
                {"sourceLocale":"ja","priceRows":[
                  {"evidence":"| 5月 | 4.8万円 | 1万2,000円 |","plan":"5月","category":"大人","amountText":"4.8万円","amount":4800,"currency":"JPY"}],
                 "salePeriod":{"evidence":"","start":"","end":""},"cancelRules":[],"sections":[],
                 "description":{"highlights":[],"summary":""}}
                """);
        // 재계산(4.8万 = 48000)과 표 결합(4800 은 표의 어느 칸에도 없다) 둘 다 잡는다
        assertThat(facts(d)).hasSize(2).anyMatch(p -> p.contains("48000")).anyMatch(p -> p.contains("어느 칸에도 없습니다"));
        assertThat(ExtractCheck.dropInvalid(d, DOC)).isEqualTo(1);
        assertThat(d.path("priceRows")).isEmpty();
    }

    @Test
    void 판매기간은_근거의_첫_마지막_날짜와_같아야() throws Exception {
        ObjectNode d = draft("""
                {"sourceLocale":"ja","priceRows":[],"cancelRules":[],"sections":[],"description":{"highlights":[],"summary":""},
                 "salePeriod":{"evidence":"販売期間 2026/4/1〜2027/2/28","start":"2026-04-01","end":"2027-03-28"}}
                """);
        assertThat(facts(d)).singleElement().asString().contains("2027-02-28");
    }

    @Test
    void 설명_lint_통과() throws Exception {
        ObjectNode d = draft("""
                {"sourceLocale":"ja","priceRows":[],"cancelRules":[],"sections":[],"salePeriod":{"evidence":"","start":"","end":""},
                 "description":{"highlights":["博多港からフェリー太古で上五島へ","帰りはANAで福岡空港着","3〜5日間から選べる島旅"],
                   "summary":"博多港からフェリーで上五島へ渡り、帰りは飛行機で福岡空港に戻るツアーです。"}}
                """);
        assertThat(facts(d)).isEmpty();
    }

    @Test
    void 설명에_문서에_없는_숫자나_반복이_있으면_실패() throws Exception {
        ObjectNode d = draft("""
                {"sourceLocale":"ja","priceRows":[],"cancelRules":[],"sections":[],"salePeriod":{"evidence":"","start":"","end":""},
                 "description":{"highlights":["博多港からフェリーで上五島へ","博多港からフェリーで上五島へ","全9日間の島旅です"],
                   "summary":"上五島をめぐるツアーです。"}}
                """);
        assertThat(ExtractCheck.problems(d, DOC)).anyMatch(p -> p.contains("반복")).anyMatch(p -> p.contains("'9'"));
        ExtractCheck.dropInvalid(d, DOC);
        assertThat(d.path("description").path("summary").asText()).isEmpty();
    }

    @Test
    void 설명_개수_길이_언어() throws Exception {
        ObjectNode d = draft("""
                {"sourceLocale":"ja","priceRows":[],"cancelRules":[],"sections":[],"salePeriod":{"evidence":"","start":"","end":""},
                 "description":{"highlights":["A ferry trip from Hakata port to Kamigoto island"],
                   "summary":"A relaxing island trip with ferry and flight included for everyone."}}
                """);
        assertThat(ExtractCheck.problems(d, DOC))
                .anyMatch(p -> p.contains("3~5개"))
                .anyMatch(p -> p.contains("40자"))
                .anyMatch(p -> p.contains("문서 언어"));
    }

    @Test
    void 한국어_띄어쓰기가_없으면_실패() {
        String doc = ExtractCheck.canon("부산출발 오사카 2박3일 자유여행 상품입니다");
        String noSpace = "부산에서출발해오사카를둘러보는자유여행상품으로숙박과항공이포함되어있습니다";
        assertThat(ExtractCheck.prose("x", noSpace, java.util.List.of(noSpace), doc, "ko")).anyMatch(p -> p.contains("띄어쓰기"));
        String ok = "부산에서 출발해 오사카를 둘러보는 자유여행 상품으로 숙박과 항공이 포함되어 있습니다";
        assertThat(ExtractCheck.prose("x", ok, java.util.List.of(ok), doc, "ko")).isEmpty();
    }

    @Test
    void 섹션은_문제가_있으면_그_섹션만_버린다() throws Exception {
        ObjectNode d = draft("""
                {"sourceLocale":"ja","priceRows":[],"cancelRules":[],"salePeriod":{"evidence":"","start":"","end":""},
                 "description":{"highlights":[],"summary":""},
                 "sections":[{"kind":"itinerary","title":"","text":"1日目: 22:30 博多港で受付、23:45 出発"},
                             {"kind":"transport","title":"","text":"10:15 福岡空港着の便を利用します"}]}
                """);
        ExtractCheck.dropInvalid(d, DOC);
        assertThat(d.path("sections")).hasSize(1);
        assertThat(d.path("sections").get(0).path("kind").asText()).isEqualTo("itinerary");
    }

    @Test
    void other_섹션은_제목이_있어야_하고_점선은_남기지_않는다() throws Exception {
        ObjectNode d = draft("""
                {"sourceLocale":"ja","priceRows":[],"cancelRules":[],"salePeriod":{"evidence":"","start":"","end":""},
                 "description":{"highlights":[],"summary":""},
                 "sections":[{"kind":"other","title":"","text":"添乗員は同行しません。"},
                             {"kind":"itinerary","title":"","text":"1日目: 博多港 ······ ホテル"},
                             {"kind":"other","title":"ご旅行代金に含まれるもの","text":"往復のフェリー代金と朝食が含まれます。"}]}
                """);
        assertThat(ExtractCheck.problems(d, DOC)).anyMatch(p -> p.contains("sections[0].title"))
                .anyMatch(p -> p.contains("sections[1]") && p.contains("점선"));
        ExtractCheck.dropInvalid(d, DOC);
        assertThat(d.path("sections")).hasSize(1);
        assertThat(d.path("sections").get(0).path("title").asText()).isEqualTo("ご旅行代金に含まれるもの");
    }

    // ── 결합 검사·할인 칸·일정·누락 검사 (壱岐 상품에서 조성금 6,100円 이 3日間 요금으로 들어간 사고) ──

    static final String IKI = """
            【博多港発着】フェリー＆ジェットフォイルで行く！壱岐の島旅 2～4日間
            | 日数 | 大人 | 小人 |
            | 2日間 | 16,800円 | 11,750円 |
            この商品は国の特定有人国境離島地域社会維持推進交付金により、大人お一人様6,100円を割引しています。（小人・おひとり様3,050円を割引。）
            1日目 博多港 → 郷ノ浦港 ホテル（泊）
            最終日 ホテル → 郷ノ浦港 → 博多港
            ●船舶会社：九州郵船㈱
            ●宿泊：勝丸荘（基本ホテル）
            ●食事回数：朝食0～3回 又はなし
            ●添乗員：同行しません
            """;

    @Test
    void 할인액이_요금_칸에_들어가면_잡아서_버린다() throws Exception {
        ObjectNode d = draft("""
                {"sourceLocale":"ja","cancelRules":[],"sections":[],"itinerary":[],"salePeriod":{"evidence":"","start":"","end":""},
                 "description":{"highlights":[],"summary":""},
                 "priceRows":[
                   {"evidence":"| 2日間 | 16,800円 | 11,750円 |","plan":"2日間","category":"大人","amountText":"16,800円","amount":16800,"currency":"JPY"},
                   {"evidence":"大人お一人様6,100円を割引しています。","plan":"3日間","category":"大人","amountText":"6,100円","amount":6100,"currency":"JPY"}],
                 "adjustments":[
                   {"evidence":"大人お一人様6,100円を割引しています。","kind":"discount","label":"交付金","category":"大人","amountText":"6,100円","amount":6100}]}
                """);
        java.util.List<String> problems = ExtractCheck.problems(d, IKI);
        assertThat(problems).anyMatch(p -> p.contains("priceRows[1]") && p.contains("adjustments"))
                .anyMatch(p -> p.contains("플랜 '3日間'"))
                .anyMatch(p -> p.contains("더 쌉니다"))
                .noneMatch(p -> p.contains("priceRows[0]") || p.contains("adjustments[0]"));
        ExtractCheck.dropInvalid(d, IKI);
        assertThat(d.path("priceRows")).hasSize(1);
        assertThat(d.path("adjustments")).hasSize(1);
    }

    @Test
    void 일정은_그날_원문의_숫자만_순서대로() throws Exception {
        ObjectNode d = draft("""
                {"sourceLocale":"ja","priceRows":[],"adjustments":[],"cancelRules":[],"sections":[],"salePeriod":{"evidence":"","start":"","end":""},
                 "description":{"highlights":[],"summary":""},
                 "itinerary":[
                   {"evidence":"最終日 ホテル → 郷ノ浦港 → 博多港","day":"最終日","text":"ホテル → 郷ノ浦港 → 博多港"},
                   {"evidence":"1日目 博多港 → 郷ノ浦港 ホテル（泊）","day":"1日目","text":"博多港 23:45発 → 郷ノ浦港 ホテル（泊）"}]}
                """);
        java.util.List<String> problems = ExtractCheck.problems(d, IKI);
        assertThat(problems).anyMatch(p -> p.contains("itinerary[1].text") && p.contains("23"))
                .anyMatch(p -> p.contains("순서"));
        ExtractCheck.dropInvalid(d, IKI);
        assertThat(d.path("itinerary")).hasSize(1);
    }

    // ── 블록 기준 검사: 출처 블록·표 칸 결합·누락 블록 (주제 키워드 없이 어느 사이트에나 같은 방식) ──

    static final String TABLE_DOC = """
            ## 旅行代金
            | 日数 | 大人 | 小人 |
            | --- | --- | --- |
            | 2日間 | 16,800円 | 11,750円 |
            | 3日間 | 22,900円 | 14,800円 |

            ## 宿泊
            勝丸荘（基本ホテル）2～4名1室

            ## 交通
            九州郵船のフェリー
            """;

    ExtractCheck.Ctx tableCtx() {
        java.util.List<Blocks.Block> blocks = Blocks.split(TABLE_DOC);
        return ExtractCheck.Ctx.of(blocks, java.util.Map.of("B1", "price", "B2", "lodging", "B3", "transport"));
    }

    @Test
    void 표의_다른_칸_금액이면_행과_열을_알려준다() throws Exception {
        ObjectNode d = draft("""
                {"priceRows":[
                   {"block":"B1","evidence":"| 3日間 | 22,900円 | 14,800円 |","plan":"2日間","category":"大人","amountText":"22,900円","amount":22900,"currency":"JPY"},
                   {"block":"B1","evidence":"| 3日間 | 22,900円 | 14,800円 |","plan":"3日間","category":"小人","amountText":"14,800円","amount":14800,"currency":"JPY"}],
                 "sections":[{"kind":"lodging","title":"宿泊","text":"勝丸荘","blocks":["B2"]},{"kind":"transport","title":"交通","text":"九州郵船","blocks":["B3"]}]}
                """);
        java.util.List<String> problems = ExtractCheck.problems(d, tableCtx());
        assertThat(problems).anyMatch(p -> p.contains("priceRows[0]") && p.contains("3日間") && p.contains("大人"))
                .noneMatch(p -> p.contains("priceRows[1]"));
    }

    @Test
    void 어느_항목도_가리키지_않은_내용_블록을_찾는다() throws Exception {
        ObjectNode d = draft("""
                {"priceRows":[{"block":"B1","evidence":"| 2日間 | 16,800円 | 11,750円 |","plan":"2日間","category":"大人","amountText":"16,800円","amount":16800,"currency":"JPY"}],
                 "sections":[{"kind":"lodging","title":"宿泊","text":"勝丸荘","blocks":["B2"]}]}
                """);
        ExtractCheck.Ctx ctx = tableCtx();
        assertThat(ExtractCheck.uncovered(d, ctx)).extracting(Blocks.Block::id).containsExactly("B3");
        assertThat(ExtractCheck.problems(d, ctx)).anyMatch(p -> p.contains("B3(transport)") && p.contains("交通"));
    }

    @Test
    void 근거가_적은_블록_밖에_있으면_잡는다() throws Exception {
        ObjectNode d = draft("""
                {"priceRows":[{"block":"B2","evidence":"| 2日間 | 16,800円 | 11,750円 |","plan":"2日間","category":"大人","amountText":"16,800円","amount":16800,"currency":"JPY"}]}
                """);
        assertThat(ExtractCheck.problems(d, tableCtx())).anyMatch(p -> p.contains("블록 B2 안에 없습니다"));
    }

    @Test
    void 괄호_속_소인_요금을_빠뜨리면_다시_묻는다() throws Exception {
        String doc = """
                ## 基本旅行代金
                | 日数 | 大人／（　）内は小人（6～11歳） |
                | --- | --- |
                | 3日間 | 17,800円（12,500円） |
                """;
        ExtractCheck.Ctx ctx = ExtractCheck.Ctx.of(Blocks.split(doc), java.util.Map.of("B1", "price"));
        ObjectNode onlyFirst = draft("""
                {"priceRows":[{"block":"B1","evidence":"| 3日間 | 17,800円（12,500円） |","plan":"3日間","category":"小人(6~11歳)","amountText":"17,800円","amount":17800,"currency":"JPY"}]}
                """);
        assertThat(ExtractCheck.problems(onlyFirst, ctx)).anyMatch(p -> p.contains("괄호 속 금액 12500"));

        ObjectNode both = draft("""
                {"priceRows":[
                  {"block":"B1","evidence":"| 3日間 | 17,800円（12,500円） |","plan":"3日間","category":"大人","amountText":"17,800円","amount":17800,"currency":"JPY"},
                  {"block":"B1","evidence":"| 3日間 | 17,800円（12,500円） |","plan":"3日間","category":"小人(6~11歳)","amountText":"12,500円","amount":12500,"currency":"JPY"}]}
                """);
        assertThat(ExtractCheck.problems(both, ctx)).noneMatch(p -> p.contains("괄호 속"));
        // 표가 아니라 글로만 있어도 같은 검사
        assertThat(ExtractCheck.pairs(onlyFirst.path("priceRows"), ExtractCheck.Ctx.of("3日間 17,800円（12,500円）"))).hasSize(1);
    }
}
