package it.magius.struttura.architect.vanilla;

import it.magius.struttura.architect.Architect;
import it.magius.struttura.architect.model.Construction;
import it.magius.struttura.architect.model.EntityData;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.minecraft.world.phys.Vec3;

import java.util.*;
import java.util.stream.Stream;

/**
 * Loads vanilla Minecraft structures and converts them to Struttura constructions.
 * Dynamically discovers available structure templates at runtime - no hardcoded list.
 * This ensures compatibility with future Minecraft versions that add new structures.
 */
public class VanillaStructureLoader {

    /**
     * Result of loading a vanilla structure.
     */
    public record LoadResult(boolean success, String message, Construction construction) {}

    /**
     * Information about a discovered vanilla structure template.
     */
    public record VanillaStructureInfo(
        Identifier templateId,
        String constructionId,
        Map<String, String> titles,
        Map<String, String> shortDescriptions
    ) {}

    // Cache of discovered structures (populated at runtime)
    private static List<VanillaStructureInfo> discoveredStructures = null;

    // Known structure path prefixes that are typically monolithic/simple
    // These are hints for filtering, not an exhaustive list
    private static final Set<String> MONOLITHIC_PREFIXES = Set.of(
        "desert_pyramid",
        "jungle_pyramid",
        "igloo",
        "swamp_hut",
        "shipwreck",
        "underwater_ruin",
        "ruined_portal",
        "pillager_outpost",
        "nether_fossils",
        "end_city",
        "trial_chambers",
        "ancient_city",
        "bastion",
        "fossil"
    );

    // Paths to exclude (jigsaw connectors, empties, placeholders, etc.)
    private static final Set<String> EXCLUDED_PATTERNS = Set.of(
        "empty",
        "connector",
        "jigsaw",
        "blocks/air",       // Air placeholder blocks used by jigsaw
        "mobs/",            // Mob spawn markers
        "/air",             // Generic air placeholders
        "feature/",         // Feature markers
        "spawner",          // Spawner markers
        "placeholder"       // Generic placeholders
    );

    /**
     * Discovers all available vanilla structure templates from the game.
     * This method scans the StructureTemplateManager for available templates.
     *
     * @param level The server level
     * @return List of discovered structure infos
     */
    public static List<VanillaStructureInfo> discoverStructures(ServerLevel level) {
        if (discoveredStructures != null) {
            return discoveredStructures;
        }

        List<VanillaStructureInfo> structures = new ArrayList<>();
        StructureTemplateManager manager = level.getStructureManager();

        // Get all structure template IDs from the manager
        // In MC 1.21+, we can list templates from the data pack system
        Stream<Identifier> templateIds = manager.listTemplates();

        templateIds
            .filter(id -> id.getNamespace().equals("minecraft"))
            .filter(id -> !shouldExclude(id.getPath()))
            .forEach(id -> {
                VanillaStructureInfo info = createStructureInfo(id);
                structures.add(info);
            });

        // Sort by path for consistent ordering
        structures.sort(Comparator.comparing(info -> info.templateId().getPath()));

        discoveredStructures = structures;
        Architect.LOGGER.info("Discovered {} vanilla structure templates", structures.size());

        return structures;
    }

    /**
     * Clears the cached structure list (useful for reload).
     */
    public static void clearCache() {
        discoveredStructures = null;
    }

    /**
     * Checks if a template path should be excluded.
     */
    private static boolean shouldExclude(String path) {
        String lowerPath = path.toLowerCase();
        for (String pattern : EXCLUDED_PATTERNS) {
            if (lowerPath.contains(pattern)) {
                return true;
            }
        }
        return false;
    }

    // Supported languages for translations
    private static final String[] SUPPORTED_LANGUAGES = {"en", "it", "de", "fr", "es", "pt", "ru", "zh", "ja", "ko"};

    /**
     * Creates structure info from a template ID with auto-generated translations.
     */
    private static VanillaStructureInfo createStructureInfo(Identifier templateId) {
        String path = templateId.getPath();

        // Generate construction ID: net.minecraft.<path with dots instead of slashes>
        String constructionId = "net.minecraft." + path.replace("/", ".").replace("-", "_");

        // Generate translations for all supported languages
        Map<String, String> titles = new java.util.HashMap<>();
        Map<String, String> descriptions = new java.util.HashMap<>();

        for (String lang : SUPPORTED_LANGUAGES) {
            titles.put(lang, translateTitle(path, lang));
            descriptions.put(lang, translateDescription(path, lang));
        }

        return new VanillaStructureInfo(
            templateId,
            constructionId,
            titles,
            descriptions
        );
    }

    /**
     * Generates a human-readable title from a template path.
     * Examples:
     *   "desert_pyramid" -> "Desert Pyramid"
     *   "shipwreck/with_mast" -> "Shipwreck: With Mast"
     *   "underwater_ruin/big_brick_1" -> "Underwater Ruin: Big Brick 1"
     */
    private static String generateTitle(String path) {
        String[] parts = path.split("/");
        StringBuilder title = new StringBuilder();

        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                title.append(": ");
            }
            title.append(humanize(parts[i]));
        }

        return title.toString();
    }

    /**
     * Converts snake_case to Title Case.
     */
    private static String humanize(String str) {
        String[] words = str.split("_");
        StringBuilder result = new StringBuilder();

        for (String word : words) {
            if (!word.isEmpty()) {
                if (result.length() > 0) {
                    result.append(" ");
                }
                result.append(Character.toUpperCase(word.charAt(0)));
                if (word.length() > 1) {
                    result.append(word.substring(1));
                }
            }
        }

        return result.toString();
    }

    // Structure name translations by language
    // Format: structureKey -> { lang -> translation }
    private static final Map<String, Map<String, String>> STRUCTURE_TITLES = Map.ofEntries(
        Map.entry("desert_pyramid", Map.of(
            "en", "Desert Pyramid", "it", "Piramide del Deserto", "de", "Wüstenpyramide",
            "fr", "Pyramide du Désert", "es", "Pirámide del Desierto", "pt", "Pirâmide do Deserto",
            "ru", "Пустынная пирамида", "zh", "沙漠神殿", "ja", "砂漠の寺院", "ko", "사막 피라미드"
        )),
        Map.entry("jungle_pyramid", Map.of(
            "en", "Jungle Temple", "it", "Tempio della Giungla", "de", "Dschungeltempel",
            "fr", "Temple de la Jungle", "es", "Templo de la Jungla", "pt", "Templo da Selva",
            "ru", "Храм в джунглях", "zh", "丛林神殿", "ja", "ジャングルの寺院", "ko", "정글 사원"
        )),
        Map.entry("swamp_hut", Map.of(
            "en", "Witch Hut", "it", "Capanna della Strega", "de", "Hexenhütte",
            "fr", "Cabane de Sorcière", "es", "Cabaña de Bruja", "pt", "Cabana da Bruxa",
            "ru", "Хижина ведьмы", "zh", "沼泽小屋", "ja", "魔女の小屋", "ko", "마녀 오두막"
        )),
        Map.entry("igloo", Map.of(
            "en", "Igloo", "it", "Igloo", "de", "Iglu",
            "fr", "Igloo", "es", "Iglú", "pt", "Iglu",
            "ru", "Иглу", "zh", "雪屋", "ja", "イグルー", "ko", "이글루"
        )),
        Map.entry("shipwreck", Map.of(
            "en", "Shipwreck", "it", "Relitto", "de", "Schiffswrack",
            "fr", "Épave", "es", "Naufragio", "pt", "Naufrágio",
            "ru", "Затонувший корабль", "zh", "沉船", "ja", "難破船", "ko", "난파선"
        )),
        Map.entry("underwater_ruin", Map.of(
            "en", "Ocean Ruins", "it", "Rovine Oceaniche", "de", "Ozeanruinen",
            "fr", "Ruines Sous-Marines", "es", "Ruinas Oceánicas", "pt", "Ruínas Oceânicas",
            "ru", "Подводные руины", "zh", "海底废墟", "ja", "海底遺跡", "ko", "해저 유적"
        )),
        Map.entry("ruined_portal", Map.of(
            "en", "Ruined Portal", "it", "Portale in Rovina", "de", "Portalruine",
            "fr", "Portail en Ruine", "es", "Portal en Ruinas", "pt", "Portal em Ruínas",
            "ru", "Разрушенный портал", "zh", "废弃传送门", "ja", "荒廃したポータル", "ko", "폐허가 된 차원문"
        )),
        Map.entry("pillager_outpost", Map.of(
            "en", "Pillager Outpost", "it", "Avamposto dei Razziatori", "de", "Plünderer-Außenposten",
            "fr", "Avant-poste de Pillards", "es", "Puesto de Saqueadores", "pt", "Posto Avançado de Saqueadores",
            "ru", "Аванпост разбойников", "zh", "掠夺者前哨站", "ja", "略奪者の前哨基地", "ko", "약탈자 전초기지"
        )),
        Map.entry("nether_fossils", Map.of(
            "en", "Nether Fossil", "it", "Fossile del Nether", "de", "Netherfossil",
            "fr", "Fossile du Nether", "es", "Fósil del Nether", "pt", "Fóssil do Nether",
            "ru", "Адское ископаемое", "zh", "下界化石", "ja", "ネザーの化石", "ko", "네더 화석"
        )),
        Map.entry("end_city", Map.of(
            "en", "End City", "it", "Città dell'End", "de", "Endstadt",
            "fr", "Cité de l'End", "es", "Ciudad del End", "pt", "Cidade do End",
            "ru", "Город Края", "zh", "末地城", "ja", "エンドシティ", "ko", "엔드 시티"
        )),
        Map.entry("trial_chambers", Map.of(
            "en", "Trial Chambers", "it", "Camera delle Prove", "de", "Prüfungskammer",
            "fr", "Chambre d'Épreuve", "es", "Cámara de Pruebas", "pt", "Câmara de Provas",
            "ru", "Испытательная камера", "zh", "试炼密室", "ja", "トライアルチャンバー", "ko", "시련의 방"
        )),
        Map.entry("ancient_city", Map.of(
            "en", "Ancient City", "it", "Città Antica", "de", "Antike Stadt",
            "fr", "Cité Antique", "es", "Ciudad Antigua", "pt", "Cidade Antiga",
            "ru", "Древний город", "zh", "远古城市", "ja", "古代都市", "ko", "고대 도시"
        )),
        Map.entry("bastion", Map.of(
            "en", "Bastion Remnant", "it", "Bastione", "de", "Bastionsruine",
            "fr", "Vestige de Bastion", "es", "Bastión en Ruinas", "pt", "Restos de Bastião",
            "ru", "Руины бастиона", "zh", "堡垒遗迹", "ja", "砦の遺跡", "ko", "보루 잔해"
        )),
        Map.entry("fossil", Map.of(
            "en", "Fossil", "it", "Fossile", "de", "Fossil",
            "fr", "Fossile", "es", "Fósil", "pt", "Fóssil",
            "ru", "Ископаемое", "zh", "化石", "ja", "化石", "ko", "화석"
        )),
        Map.entry("village", Map.of(
            "en", "Village", "it", "Villaggio", "de", "Dorf",
            "fr", "Village", "es", "Aldea", "pt", "Vila",
            "ru", "Деревня", "zh", "村庄", "ja", "村", "ko", "마을"
        )),
        Map.entry("mansion", Map.of(
            "en", "Woodland Mansion", "it", "Magione del Bosco", "de", "Waldanwesen",
            "fr", "Manoir", "es", "Mansión del Bosque", "pt", "Mansão da Floresta",
            "ru", "Лесной особняк", "zh", "林地府邸", "ja", "森の洋館", "ko", "삼림 대저택"
        )),
        Map.entry("stronghold", Map.of(
            "en", "Stronghold", "it", "Roccaforte", "de", "Festung",
            "fr", "Forteresse", "es", "Fortaleza", "pt", "Fortaleza",
            "ru", "Крепость", "zh", "要塞", "ja", "要塞", "ko", "요새"
        )),
        Map.entry("mineshaft", Map.of(
            "en", "Mineshaft", "it", "Miniera Abbandonata", "de", "Verlassener Minenschacht",
            "fr", "Mine Abandonnée", "es", "Mina Abandonada", "pt", "Mina Abandonada",
            "ru", "Заброшенная шахта", "zh", "废弃矿井", "ja", "廃坑", "ko", "폐광"
        )),
        Map.entry("ocean_monument", Map.of(
            "en", "Ocean Monument", "it", "Monumento Oceanico", "de", "Ozeanmonument",
            "fr", "Monument Océanique", "es", "Monumento Oceánico", "pt", "Monumento Oceânico",
            "ru", "Подводный храм", "zh", "海底神殿", "ja", "海底神殿", "ko", "해저 신전"
        ))
    );

    // Structure descriptions by language
    private static final Map<String, Map<String, String>> STRUCTURE_DESCRIPTIONS = Map.ofEntries(
        Map.entry("desert_pyramid", Map.of(
            "en", "A mysterious pyramid found in deserts, containing hidden treasures and traps.",
            "it", "Una misteriosa piramide trovata nei deserti, contenente tesori nascosti e trappole.",
            "de", "Eine geheimnisvolle Pyramide in Wüsten, mit versteckten Schätzen und Fallen.",
            "fr", "Une pyramide mystérieuse trouvée dans les déserts, contenant des trésors cachés et des pièges.",
            "es", "Una pirámide misteriosa encontrada en desiertos, con tesoros ocultos y trampas.",
            "pt", "Uma pirâmide misteriosa encontrada em desertos, contendo tesouros escondidos e armadilhas.",
            "ru", "Таинственная пирамида в пустыне со скрытыми сокровищами и ловушками.",
            "zh", "沙漠中的神秘金字塔，藏有宝藏和陷阱。",
            "ja", "砂漠にある謎のピラミッド。隠された宝と罠がある。",
            "ko", "사막에서 발견되는 신비로운 피라미드로, 숨겨진 보물과 함정이 있습니다."
        )),
        Map.entry("jungle_pyramid", Map.of(
            "en", "An ancient temple hidden in the jungle with puzzles and treasure.",
            "it", "Un antico tempio nascosto nella giungla con enigmi e tesori.",
            "de", "Ein alter Tempel im Dschungel mit Rätseln und Schätzen.",
            "fr", "Un temple ancien caché dans la jungle avec des énigmes et des trésors.",
            "es", "Un templo antiguo escondido en la jungla con acertijos y tesoros.",
            "pt", "Um templo antigo escondido na selva com quebra-cabeças e tesouros.",
            "ru", "Древний храм в джунглях с загадками и сокровищами.",
            "zh", "隐藏在丛林中的古老神殿，有谜题和宝藏。",
            "ja", "ジャングルに隠された古代の寺院。パズルと宝がある。",
            "ko", "정글에 숨겨진 고대 사원으로, 퍼즐과 보물이 있습니다."
        )),
        Map.entry("swamp_hut", Map.of(
            "en", "A creepy hut on stilts found in swamps, home to a witch.",
            "it", "Una inquietante capanna su palafitte trovata nelle paludi, dimora di una strega.",
            "de", "Eine unheimliche Hütte auf Stelzen in Sümpfen, Heimat einer Hexe.",
            "fr", "Une cabane effrayante sur pilotis dans les marais, demeure d'une sorcière.",
            "es", "Una cabaña espeluznante sobre pilotes en pantanos, hogar de una bruja.",
            "pt", "Uma cabana assustadora sobre palafitas em pântanos, lar de uma bruxa.",
            "ru", "Жуткая хижина на сваях в болоте, дом ведьмы.",
            "zh", "沼泽中的高脚小屋，是女巫的住所。",
            "ja", "沼地にある不気味な高床式の小屋。魔女の家。",
            "ko", "늪지대에서 발견되는 기둥 위의 오싹한 오두막으로, 마녀의 집입니다."
        )),
        Map.entry("igloo", Map.of(
            "en", "A cozy snow shelter found in snowy biomes.",
            "it", "Un accogliente rifugio di neve trovato nei biomi innevati.",
            "de", "Eine gemütliche Schneehütte in verschneiten Biomen.",
            "fr", "Un abri de neige confortable trouvé dans les biomes enneigés.",
            "es", "Un acogedor refugio de nieve encontrado en biomas nevados.",
            "pt", "Um abrigo de neve aconchegante encontrado em biomas nevados.",
            "ru", "Уютное снежное убежище в заснеженных биомах.",
            "zh", "雪地生物群系中舒适的雪屋。",
            "ja", "雪のバイオームにある居心地の良い雪の避難所。",
            "ko", "눈 덮인 생물 군계에서 발견되는 아늑한 눈 쉼터입니다."
        )),
        Map.entry("shipwreck", Map.of(
            "en", "A sunken ship found underwater.",
            "it", "Una nave affondata trovata sott'acqua.",
            "de", "Ein gesunkenes Schiff unter Wasser.",
            "fr", "Un navire coulé trouvé sous l'eau.",
            "es", "Un barco hundido encontrado bajo el agua.",
            "pt", "Um navio naufragado encontrado debaixo d'água.",
            "ru", "Затонувший корабль под водой.",
            "zh", "水下发现的沉船。",
            "ja", "水中で見つかる沈没船。",
            "ko", "수중에서 발견되는 침몰한 배입니다."
        )),
        Map.entry("underwater_ruin", Map.of(
            "en", "Ancient underwater ruins from a lost civilization.",
            "it", "Antiche rovine sottomarine di una civiltà perduta.",
            "de", "Alte Unterwasserruinen einer verlorenen Zivilisation.",
            "fr", "Ruines sous-marines anciennes d'une civilisation perdue.",
            "es", "Ruinas submarinas antiguas de una civilización perdida.",
            "pt", "Ruínas subaquáticas antigas de uma civilização perdida.",
            "ru", "Древние подводные руины потерянной цивилизации.",
            "zh", "失落文明的古老海底废墟。",
            "ja", "失われた文明の古代の水中遺跡。",
            "ko", "잃어버린 문명의 고대 수중 유적입니다."
        )),
        Map.entry("ruined_portal", Map.of(
            "en", "A partially destroyed nether portal from the past.",
            "it", "Un portale del Nether parzialmente distrutto dal passato.",
            "de", "Ein teilweise zerstörtes Netherportal aus der Vergangenheit.",
            "fr", "Un portail du Nether partiellement détruit du passé.",
            "es", "Un portal del Nether parcialmente destruido del pasado.",
            "pt", "Um portal do Nether parcialmente destruído do passado.",
            "ru", "Частично разрушенный портал Нижнего мира из прошлого.",
            "zh", "来自过去的部分损坏的下界传送门。",
            "ja", "過去から残る部分的に破壊されたネザーポータル。",
            "ko", "과거에서 온 부분적으로 파괴된 네더 차원문입니다."
        )),
        Map.entry("pillager_outpost", Map.of(
            "en", "A structure used by pillagers as a base.",
            "it", "Una struttura usata dai razziatori come base.",
            "de", "Eine Struktur, die von Plünderern als Basis genutzt wird.",
            "fr", "Une structure utilisée par les pillards comme base.",
            "es", "Una estructura usada por saqueadores como base.",
            "pt", "Uma estrutura usada por saqueadores como base.",
            "ru", "Структура, используемая разбойниками как база.",
            "zh", "掠夺者用作基地的建筑。",
            "ja", "略奪者が拠点として使用する構造物。",
            "ko", "약탈자들이 기지로 사용하는 구조물입니다."
        )),
        Map.entry("nether_fossils", Map.of(
            "en", "Ancient bone structures found in soul sand valleys.",
            "it", "Antiche strutture ossee trovate nelle valli di sabbia delle anime.",
            "de", "Alte Knochenstrukturen in Seelensandtälern.",
            "fr", "Structures osseuses anciennes trouvées dans les vallées de sable des âmes.",
            "es", "Estructuras óseas antiguas encontradas en valles de arena de almas.",
            "pt", "Estruturas ósseas antigas encontradas em vales de areia das almas.",
            "ru", "Древние костные структуры в долинах песка душ.",
            "zh", "灵魂沙峡谷中发现的古老骨骼结构。",
            "ja", "ソウルサンドの谷で見つかる古代の骨の構造物。",
            "ko", "영혼 모래 계곡에서 발견되는 고대 뼈 구조물입니다."
        )),
        Map.entry("end_city", Map.of(
            "en", "A mystical city found in the End dimension.",
            "it", "Una città mistica trovata nella dimensione dell'End.",
            "de", "Eine mystische Stadt in der End-Dimension.",
            "fr", "Une ville mystique trouvée dans la dimension de l'End.",
            "es", "Una ciudad mística encontrada en la dimensión del End.",
            "pt", "Uma cidade mística encontrada na dimensão do End.",
            "ru", "Мистический город в измерении Края.",
            "zh", "末地维度中的神秘城市。",
            "ja", "エンド次元で見つかる神秘的な都市。",
            "ko", "엔드 차원에서 발견되는 신비로운 도시입니다."
        )),
        Map.entry("trial_chambers", Map.of(
            "en", "A challenging dungeon with trials and rewards.",
            "it", "Un dungeon impegnativo con prove e ricompense.",
            "de", "Ein herausfordernder Dungeon mit Prüfungen und Belohnungen.",
            "fr", "Un donjon difficile avec des épreuves et des récompenses.",
            "es", "Una mazmorra desafiante con pruebas y recompensas.",
            "pt", "Uma masmorra desafiadora com provas e recompensas.",
            "ru", "Сложное подземелье с испытаниями и наградами.",
            "zh", "充满试炼和奖励的地牢。",
            "ja", "試練と報酬のある挑戦的なダンジョン。",
            "ko", "시련과 보상이 있는 도전적인 던전입니다."
        )),
        Map.entry("ancient_city", Map.of(
            "en", "A massive underground city with dark secrets.",
            "it", "Una massiccia città sotterranea con oscuri segreti.",
            "de", "Eine riesige unterirdische Stadt mit dunklen Geheimnissen.",
            "fr", "Une immense cité souterraine avec de sombres secrets.",
            "es", "Una ciudad subterránea masiva con oscuros secretos.",
            "pt", "Uma cidade subterrânea massiva com segredos sombrios.",
            "ru", "Огромный подземный город с тёмными секретами.",
            "zh", "拥有黑暗秘密的巨大地下城市。",
            "ja", "暗い秘密を持つ巨大な地下都市。",
            "ko", "어두운 비밀을 가진 거대한 지하 도시입니다."
        )),
        Map.entry("bastion", Map.of(
            "en", "A fortified piglin structure in the Nether.",
            "it", "Una struttura fortificata dei piglin nel Nether.",
            "de", "Eine befestigte Piglin-Struktur im Nether.",
            "fr", "Une structure fortifiée de piglins dans le Nether.",
            "es", "Una estructura fortificada de piglins en el Nether.",
            "pt", "Uma estrutura fortificada de piglins no Nether.",
            "ru", "Укреплённое строение пиглинов в Нижнем мире.",
            "zh", "下界中猪灵的堡垒。",
            "ja", "ネザーにあるピグリンの要塞。",
            "ko", "네더에 있는 피글린의 요새 구조물입니다."
        )),
        Map.entry("fossil", Map.of(
            "en", "Fossilized remains of ancient creatures.",
            "it", "Resti fossilizzati di creature antiche.",
            "de", "Versteinerte Überreste alter Kreaturen.",
            "fr", "Restes fossilisés de créatures anciennes.",
            "es", "Restos fosilizados de criaturas antiguas.",
            "pt", "Restos fossilizados de criaturas antigas.",
            "ru", "Окаменелые останки древних существ.",
            "zh", "古代生物的化石遗骸。",
            "ja", "古代生物の化石化した遺骸。",
            "ko", "고대 생물의 화석화된 유해입니다."
        )),
        Map.entry("village", Map.of(
            "en", "A settlement of villagers.",
            "it", "Un insediamento di villager.",
            "de", "Eine Siedlung von Dorfbewohnern.",
            "fr", "Un village de villageois.",
            "es", "Un asentamiento de aldeanos.",
            "pt", "Um assentamento de aldeões.",
            "ru", "Поселение жителей деревни.",
            "zh", "村民的聚居地。",
            "ja", "村人の集落。",
            "ko", "주민들의 정착지입니다."
        )),
        Map.entry("mansion", Map.of(
            "en", "A massive woodland mansion filled with illagers.",
            "it", "Una massiccia magione del bosco piena di illager.",
            "de", "Ein riesiges Waldanwesen voller Illager.",
            "fr", "Un immense manoir forestier rempli d'illageois.",
            "es", "Una mansión masiva del bosque llena de illagers.",
            "pt", "Uma mansão massiva da floresta cheia de illagers.",
            "ru", "Огромный лесной особняк, полный иллагеров.",
            "zh", "充满灾厄村民的巨大林地府邸。",
            "ja", "邪悪な村人でいっぱいの巨大な森の洋館。",
            "ko", "변명자들로 가득 찬 거대한 삼림 대저택입니다."
        )),
        Map.entry("stronghold", Map.of(
            "en", "An underground fortress with an End portal.",
            "it", "Una fortezza sotterranea con un portale dell'End.",
            "de", "Eine unterirdische Festung mit einem Endportal.",
            "fr", "Une forteresse souterraine avec un portail de l'End.",
            "es", "Una fortaleza subterránea con un portal del End.",
            "pt", "Uma fortaleza subterrânea com um portal do End.",
            "ru", "Подземная крепость с порталом Края.",
            "zh", "带有末地传送门的地下要塞。",
            "ja", "エンドポータルのある地下要塞。",
            "ko", "엔드 차원문이 있는 지하 요새입니다."
        )),
        Map.entry("mineshaft", Map.of(
            "en", "An abandoned mine with rails and resources.",
            "it", "Una miniera abbandonata con rotaie e risorse.",
            "de", "Eine verlassene Mine mit Schienen und Ressourcen.",
            "fr", "Une mine abandonnée avec des rails et des ressources.",
            "es", "Una mina abandonada con raíles y recursos.",
            "pt", "Uma mina abandonada com trilhos e recursos.",
            "ru", "Заброшенная шахта с рельсами и ресурсами.",
            "zh", "带有铁轨和资源的废弃矿井。",
            "ja", "レールと資源のある廃坑。",
            "ko", "레일과 자원이 있는 버려진 광산입니다."
        )),
        Map.entry("ocean_monument", Map.of(
            "en", "A massive underwater temple guarded by guardians.",
            "it", "Un massiccio tempio sottomarino protetto da guardiani.",
            "de", "Ein riesiger Unterwassertempel, bewacht von Wächtern.",
            "fr", "Un temple sous-marin massif gardé par des gardiens.",
            "es", "Un templo submarino masivo custodiado por guardianes.",
            "pt", "Um templo subaquático massivo guardado por guardiões.",
            "ru", "Огромный подводный храм, охраняемый стражами.",
            "zh", "由守卫者保护的巨大海底神殿。",
            "ja", "ガーディアンに守られた巨大な海底神殿。",
            "ko", "가디언이 지키는 거대한 수중 사원입니다."
        ))
    );

    /**
     * Translates a structure title to the specified language.
     */
    private static String translateTitle(String path, String lang) {
        String firstPart = path.contains("/") ? path.substring(0, path.indexOf("/")) : path;

        Map<String, String> translations = STRUCTURE_TITLES.get(firstPart);
        if (translations != null) {
            String baseTitle = translations.getOrDefault(lang, translations.get("en"));
            if (path.contains("/")) {
                String rest = path.substring(path.indexOf("/") + 1);
                return baseTitle + ": " + humanize(rest);
            }
            return baseTitle;
        }

        // Fallback: generate English title
        return generateTitle(path);
    }

    /**
     * Translates a structure description to the specified language.
     */
    private static String translateDescription(String path, String lang) {
        String firstPart = path.contains("/") ? path.substring(0, path.indexOf("/")) : path;

        Map<String, String> translations = STRUCTURE_DESCRIPTIONS.get(firstPart);
        if (translations != null) {
            return translations.getOrDefault(lang, translations.get("en"));
        }

        // Fallback descriptions by language
        return switch (lang) {
            case "it" -> "Una struttura vanilla di Minecraft.";
            case "de" -> "Eine Vanilla-Minecraft-Struktur.";
            case "fr" -> "Une structure Minecraft vanilla.";
            case "es" -> "Una estructura vanilla de Minecraft.";
            case "pt" -> "Uma estrutura vanilla do Minecraft.";
            case "ru" -> "Ванильная структура Minecraft.";
            case "zh" -> "原版Minecraft结构。";
            case "ja" -> "バニラのMinecraft構造物。";
            case "ko" -> "바닐라 마인크래프트 구조물입니다.";
            default -> "A vanilla Minecraft structure.";
        };
    }

    /**
     * Loads a vanilla structure template and converts it to a Struttura Construction.
     *
     * @param level The server level (needed for StructureTemplateManager)
     * @param info The structure info with template ID and translations
     * @param skipLootChests If true, skip loot table data from chests (keep chest block, remove loot reference)
     * @return LoadResult with success status and the construction if successful
     */
    public static LoadResult loadStructure(ServerLevel level, VanillaStructureInfo info, boolean skipLootChests) {
        try {
            StructureTemplateManager manager = level.getStructureManager();

            // Load the template
            Optional<StructureTemplate> templateOpt = manager.get(info.templateId());
            if (templateOpt.isEmpty()) {
                return new LoadResult(false, "Template not found: " + info.templateId(), null);
            }

            StructureTemplate template = templateOpt.get();

            // Create the construction
            Construction construction = new Construction(
                info.constructionId(),
                new UUID(0, 0), // Minecraft vanilla UUID
                "Minecraft"
            );

            // Set translations
            for (var entry : info.titles().entrySet()) {
                construction.setTitle(entry.getKey(), entry.getValue());
            }
            for (var entry : info.shortDescriptions().entrySet()) {
                construction.setShortDescription(entry.getKey(), entry.getValue());
            }

            // Get template size
            var size = template.getSize();
            Architect.LOGGER.debug("Loading template {} with size {}x{}x{}",
                info.templateId(), size.getX(), size.getY(), size.getZ());

            // Process blocks from template palettes
            int blocksAdded = 0;
            int chestsSkipped = 0;

            var palettes = template.palettes;
            if (palettes.isEmpty()) {
                return new LoadResult(false, "Template has no block palettes: " + info.templateId(), null);
            }

            // Use the first palette (index 0)
            var palette = palettes.get(0).blocks();

            for (StructureTemplate.StructureBlockInfo blockInfo : palette) {
                BlockPos pos = blockInfo.pos();
                BlockState state = blockInfo.state();
                CompoundTag nbt = blockInfo.nbt();

                // Skip structure blocks and jigsaw blocks (they're template markers)
                if (state.is(Blocks.STRUCTURE_BLOCK) || state.is(Blocks.JIGSAW)) {
                    continue;
                }

                // Check for loot chests
                if (skipLootChests && nbt != null) {
                    // MC 1.21+ uses "LootTable" or "loot_table" in the nbt
                    if (nbt.contains("LootTable") || nbt.contains("loot_table")) {
                        chestsSkipped++;
                        // Keep the block but remove loot table reference
                        CompoundTag cleanNbt = nbt.copy();
                        cleanNbt.remove("LootTable");
                        cleanNbt.remove("loot_table");
                        cleanNbt.remove("LootTableSeed");
                        cleanNbt.remove("loot_table_seed");

                        if (cleanNbt.isEmpty()) {
                            construction.addBlock(pos, state);
                        } else {
                            construction.addBlock(pos, state, cleanNbt);
                        }
                        blocksAdded++;
                        continue;
                    }
                }

                // Add block with or without NBT
                if (nbt != null && !nbt.isEmpty()) {
                    construction.addBlock(pos, state, nbt);
                } else {
                    construction.addBlock(pos, state);
                }
                blocksAdded++;
            }

            // Process entities from template
            int entitiesAdded = 0;
            for (StructureTemplate.StructureEntityInfo entityInfo : template.entityInfoList) {
                String entityType = getEntityTypeFromNbt(entityInfo.nbt);
                if (entityType == null || entityType.isEmpty()) {
                    continue;
                }

                Vec3 relativePos = entityInfo.pos;
                CompoundTag entityNbt = entityInfo.nbt.copy();

                // Remove UUID from entity NBT (will be regenerated on spawn)
                entityNbt.remove("UUID");

                EntityData data = new EntityData(
                    entityType,
                    relativePos,
                    0.0f, // yaw will be in NBT
                    0.0f, // pitch will be in NBT
                    entityNbt
                );

                construction.addEntity(data);
                entitiesAdded++;
            }

            // Validate that we have at least one block with valid bounds
            if (blocksAdded == 0) {
                return new LoadResult(false, "Template has no blocks: " + info.templateId(), null);
            }

            // Validate bounds are valid (not NaN or infinite)
            var bounds = construction.getBounds();
            if (bounds.getMinX() == Integer.MAX_VALUE || bounds.getMaxX() == Integer.MIN_VALUE) {
                return new LoadResult(false, "Template has invalid bounds: " + info.templateId(), null);
            }

            String message = String.format("Loaded %d blocks, %d entities (skipped loot in %d chests)",
                blocksAdded, entitiesAdded, chestsSkipped);

            Architect.LOGGER.info("Loaded vanilla structure {}: {}", info.constructionId(), message);

            return new LoadResult(true, message, construction);

        } catch (Exception e) {
            Architect.LOGGER.error("Failed to load vanilla structure: " + info.templateId(), e);
            return new LoadResult(false, "Error: " + e.getMessage(), null);
        }
    }

    /**
     * Extracts entity type from NBT.
     */
    private static String getEntityTypeFromNbt(CompoundTag nbt) {
        if (nbt == null) return null;

        // MC 1.21+ uses "id" tag for entity type
        return nbt.getString("id").orElse(null);
    }

    /**
     * Finds structure info by template path (e.g., "minecraft:desert_pyramid").
     */
    public static Optional<VanillaStructureInfo> findByTemplatePath(ServerLevel level, String templatePath) {
        return discoverStructures(level).stream()
            .filter(info -> info.templateId().toString().equals(templatePath))
            .findFirst();
    }

    /**
     * Finds structure info by construction ID (e.g., "net.minecraft.desert_pyramid").
     */
    public static Optional<VanillaStructureInfo> findByConstructionId(ServerLevel level, String constructionId) {
        return discoverStructures(level).stream()
            .filter(info -> info.constructionId().equals(constructionId))
            .findFirst();
    }

    /**
     * Gets all structures matching a search filter.
     */
    public static List<VanillaStructureInfo> searchStructures(ServerLevel level, String filter) {
        String lowerFilter = filter.toLowerCase();
        return discoverStructures(level).stream()
            .filter(info -> info.templateId().getPath().toLowerCase().contains(lowerFilter) ||
                           info.constructionId().toLowerCase().contains(lowerFilter))
            .toList();
    }
}
