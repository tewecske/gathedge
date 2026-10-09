package gathedge.shared.domain

/** How one [[WordLanguage]] handles grammatical gender and its articles, and the one fact about its verbs that the
  * dictionary import needs.
  *
  * This is the one place that names an article. Nothing outside this file may write `"der"`, `"el"` or any other
  * article literal — every display, strip, or picker call goes through here, so a fifth language is one new entry
  * rather than a grep for `WordLanguage.De`.
  *
  * `articleForms` deliberately carries more than [[definiteArticles]]' values: German's declined `den`/`dem`/`des` and
  * Spanish's plural `los`/`las` are recognised on the way in even though only the singular nominative form
  * ([[definiteArticles]]) is ever shown, since `words` rows are lemmas and a plural belongs to `word_forms` instead.
  *
  * `reflexivePronouns` are the pronouns a reflexive verb form is written with: Spanish `me quejo`, `se compra`. The
  * dictionary import tags a form that starts with one as `reflexive`, since the dump gives it the plain form's tags.
  *
  * `declinedArticles` are the definite article by number, and by case where the language has cases, for the noun form
  * tables (`dem Haus`, `der Häuser`, `los niños`). `singularArticleGender` names the nouns whose singular takes another
  * gender's article: Spanish `el agua`, a feminine noun.
  *
  * `subjectPronouns` go before each form of a verb's person tables (`ich rufe an`). `subordinator` goes before the
  * pronoun in a subordinate clause, where the form is wrong without it (`dass ich anrufe`). `elisions` shorten a prefix
  * before a vowel or `h` and join it to the form (`j'aime`, `l'homme`). `keepsPrefix` names the words that start with
  * an aspirated `h`, before which nothing elides (`le héros`, `je hais`).
  *
  * The tables use these, but [[display]] does not yet: it still writes `la agua` and `le homme`.
  */
final case class LanguageProfile(
  genders: List[Gender],
  definiteArticles: Map[Gender, String],
  articleForms: Map[String, Gender],
  capitalizesNouns: Boolean,
  reflexivePronouns: Set[String] = Set.empty,
  declinedArticles: List[TaggedWord] = Nil,
  subjectPronouns: List[TaggedWord] = Nil,
  subordinator: Option[String] = None,
  elisions: Map[String, String] = Map.empty,
  singularArticleGender: Map[String, Gender] = Map.empty,
  keepsPrefix: Set[String] = Set.empty,
) {

  def hasGenders: Boolean = genders.nonEmpty

  def article(gender: Gender): Option[String] = definiteArticles.get(gender)

  /** A gendered noun with its article in front, anything else (or a genderless language) as it stands. */
  def display(text: String, gender: Option[Gender]): String = {
    gender.flatMap(article).map(a => s"$a $text").getOrElse(text)
  }

  /** The definite article of a form table cell of `lemma` that requires `tags`. The first entry whose tags the cell
    * requires and whose gender is the noun's (or any gender) wins. A noun with no gender gets only the plural's.
    */
  def declinedArticle(gender: Option[Gender], tags: Set[String], lemma: String = ""): Option[String] = {
    val asGender = singularArticleGender.get(lemma.toLowerCase).filter(_ => tags.contains("singular"))
    TaggedWord.find(declinedArticles, asGender.orElse(gender), tags)
  }

  /** The subject pronoun of a form table cell that requires `tags`: the first entry whose tags the cell requires. */
  def subjectPronoun(tags: Set[String]): Option[String] = TaggedWord.find(subjectPronouns, None, tags)

  /** What goes before each form of a form table cell of `lemma` that requires `tags`. */
  def prefix(kind: FormPrefix, gender: Option[Gender], tags: Set[String], lemma: String = ""): Option[String] = {
    kind match {
      case FormPrefix.Article            => declinedArticle(gender, tags, lemma)
      case FormPrefix.Pronoun            => subjectPronoun(tags)
      case FormPrefix.SubordinatePronoun =>
        subjectPronoun(tags).map(pronoun => subordinator.fold(pronoun)(conjunction => s"$conjunction $pronoun"))
    }
  }

  /** `prefix` as written before `text`, a form of `lemma`: elided and joined where the form starts with a vowel or `h`
    * (`j'`), followed by a space otherwise. Only the last word of the prefix elides (`que je` becomes `que j'`).
    * Nothing elides before a form of a [[keepsPrefix]] word, so `haïr` keeps `je hais`.
    */
  def lead(prefix: String, text: String, lemma: String = ""): String = {
    val (head, last) = prefix.lastIndexOf(' ') match {
      case -1    => ("", prefix)
      case space => (prefix.take(space + 1), prefix.drop(space + 1))
    }
    elisions.get(last) match {
      case Some(elided)
          if text.headOption.exists(c => LanguageProfile.elidesBefore.contains(c.toLower)) &&
            !keepsPrefix.contains(lemma.toLowerCase) =>
        head + elided
      case _ =>
        s"$prefix "
    }
  }

  /** Splits a leading article off `text`, answering the bare word and the gender it names. Recognises every form in
    * [[articleForms]], not only the ones [[display]] shows — so a plural or declined article a reader typed still
    * strips correctly. A token with no such prefix (or a lone article with nothing after it) passes through unchanged.
    */
  def strip(text: String): (String, Option[Gender]) = {
    if (articleForms.isEmpty)
      (text, None)
    else {
      text.trim.split("\\s+", 2) match {
        case Array(head, rest) if articleForms.contains(head.toLowerCase) => (rest, articleForms.get(head.toLowerCase))
        case _                                                            => (text, None)
      }
    }
  }

  /** Capitalizes a noun exactly when this language's nouns are always capitalized (German). */
  def capitalize(text: String, gender: Option[Gender]): String = {
    if (capitalizesNouns && gender.isDefined) text.capitalize else text
  }
}

/** One article or pronoun of a [[LanguageProfile]]: the tags of the cells it goes with, and the gender it needs.
  * `gender = None` fits every gender: the German plural.
  */
final case class TaggedWord(tags: Set[String], gender: Option[Gender], text: String)

object TaggedWord {

  /** The first of `words` whose tags the cell requires and whose gender is the noun's (or any gender). */
  def find(words: List[TaggedWord], gender: Option[Gender], tags: Set[String]): Option[String] = {
    words.find(word => word.tags.subsetOf(tags) && word.gender.forall(gender.contains)).map(_.text)
  }
}

object LanguageProfile {

  /** The letters an elided prefix joins: vowels, with and without accents, and `h`. Not `y`: `le yaourt`. */
  private val elidesBefore: Set[Char] = "aeiouàâäéèêëîïôöùûüœæh".toSet

  /** The six persons in order: first, second and third singular, then plural. */
  private def persons(pronouns: String*): List[TaggedWord] = {
    val tags = for {
      number <- List("singular", "plural")
      person <- List("first-person", "second-person", "third-person")
    } yield Set(person, number)
    tags.zip(pronouns).map { case (tags, pronoun) => TaggedWord(tags, None, pronoun) }
  }

  /** A two-gender language's article by number: no case changes it. */
  private def byNumber(masculine: String, feminine: String, masculinePlural: String, femininePlural: String) = {
    List(
      TaggedWord(Set("singular"), Some(Gender.Masculine), masculine),
      TaggedWord(Set("singular"), Some(Gender.Feminine), feminine),
      TaggedWord(Set("plural"), Some(Gender.Masculine), masculinePlural),
      TaggedWord(Set("plural"), Some(Gender.Feminine), femininePlural),
    )
  }

  /** The Spanish feminine nouns that start with a stressed `a` or `ha`, so their singular takes `el` (`el agua`, `las
    * aguas`). Taken from the dump: every feminine noun whose IPA starts with a stressed `a`. Left out, as the rule
    * leaves them out: the letter names (`la a`, `la hache`, `la alfa`) and nouns for women (`la árbitra`).
    */
  private val spanishStressedA: Set[String] = {
    Set(
      "abra",
      "acta",
      "afta",
      "agua",
      "aja",
      "ala",
      "alba",
      "alca",
      "alga",
      "alma",
      "alta",
      "alza",
      "ama",
      "ampa",
      "anca",
      "ancha",
      "ancla",
      "anda",
      "angla",
      "ansa",
      "ansia",
      "anta",
      "ara",
      "arca",
      "arda",
      "aria",
      "arma",
      "arpa",
      "arras",
      "asa",
      "asca",
      "ascua",
      "asma",
      "asna",
      "aspa",
      "asta",
      "aula",
      "aura",
      "ave",
      "awa",
      "aya",
      "aña",
      "haba",
      "habla",
      "hacha",
      "hada",
      "halda",
      "hambre",
      "hampa",
      "harda",
      "harpa",
      "haya",
      "haz",
      "haza",
      "ábsida",
      "ácana",
      "ágata",
      "ágora",
      "águila",
      "álgebra",
      "álsine",
      "ámpula",
      "áncora",
      "ánfora",
      "ánima",
      "ánsara",
      "área",
    )
  }

  /** The French words with an aspirated `h`: nothing elides before them (`le héros`, `la haine`, `je hais`). Two
    * sources:
    *
    *   - every lemma in the dump's "French terms with aspirated h" category, as it stands;
    *   - common words that category misses (`haine`, `haïr`, `héros`, `hibou`, `homard`, `huit`), each one confirmed in
    *     French Wiktionary's "Termes en français à h aspiré".
    */
  private val frenchAspiratedH: Set[String] = {
    Set(
      "hache",
      "hacher",
      "hachis",
      "hachoir",
      "hachure",
      "hagar",
      "haha",
      "haie",
      "haillon",
      "haine",
      "haleter",
      "hall",
      "halle",
      "halo",
      "halogène",
      "halophile",
      "halouf",
      "halte",
      "hamac",
      "hamburger",
      "hameau",
      "hammam",
      "hampe",
      "hamster",
      "hanche",
      "handicap",
      "handicapé",
      "handisport",
      "hangar",
      "hanneton",
      "hanter",
      "hantise",
      "hanté",
      "happe",
      "happer",
      "harangue",
      "haranguer",
      "harassant",
      "harasse",
      "harasser",
      "harcelant",
      "harceler",
      "harceleur",
      "harcèlement",
      "hard",
      "harde",
      "hardes",
      "hardi",
      "hardiesse",
      "hareng",
      "hargne",
      "haricot",
      "haridelle",
      "harnachement",
      "harnacher",
      "harnais",
      "haro",
      "harpe",
      "harpie",
      "harpon",
      "hasard",
      "hasarder",
      "hashtag",
      "hauban",
      "hausse",
      "haussement",
      "hausser",
      "haussière",
      "haut",
      "hautain",
      "hautbois",
      "haute",
      "hauteur",
      "havre",
      "haïda",
      "haïe",
      "haïr",
      "haïssable",
      "heaume",
      "hennir",
      "hennissement",
      "hernie",
      "herse",
      "heu",
      "heurt",
      "heurter",
      "heurtoir",
      "heurté",
      "hiatus",
      "hibou",
      "hic",
      "hideux",
      "hier",
      "hisser",
      "hiérarchisation",
      "hiérarchiser",
      "hiératique",
      "hiéroglyphe",
      "hobereau",
      "hocher",
      "hockey",
      "hollandais",
      "hollywoodien",
      "homard",
      "hongre",
      "hongrois",
      "honnir",
      "honte",
      "honteux",
      "hopi",
      "hoquet",
      "horde",
      "hors",
      "hors-bord",
      "hors-d'œuvre",
      "hors-jeu",
      "hotte",
      "houblon",
      "houe",
      "houille",
      "houiller",
      "houillère",
      "houle",
      "houlette",
      "houleux",
      "houppe",
      "houppier",
      "hourra",
      "hourrah",
      "houspiller",
      "housse",
      "housser",
      "houx",
      "huard",
      "hublot",
      "huche",
      "huchet",
      "huer",
      "huguenot",
      "huit",
      "huitaine",
      "huitantième",
      "huitième",
      "hulotte",
      "hululeur",
      "huppe",
      "huppé",
      "hure",
      "hurlant",
      "hurlement",
      "hurler",
      "hutte",
      "huée",
      "hâbler",
      "hâbleur",
      "hâle",
      "hâler",
      "hâlé",
      "hâte",
      "hâter",
      "hâtif",
      "hère",
      "hé",
      "hébergeur",
      "hébertiste",
      "hélas",
      "hérisser",
      "hérisson",
      "hérissé",
      "héron",
      "héros",
      "hêtre",
    )
  }

  val ungendered: LanguageProfile = LanguageProfile(Nil, Map.empty, Map.empty, capitalizesNouns = false)

  /** No genders, but its verbs have pronouns. */
  private val hungarian: LanguageProfile = {
    ungendered.copy(subjectPronouns = persons("én", "te", "ő", "mi", "ti", "ők"))
  }

  private val german: LanguageProfile = LanguageProfile(
    genders = List(Gender.Masculine, Gender.Feminine, Gender.Neuter),
    definiteArticles = Map(Gender.Masculine -> "der", Gender.Feminine -> "die", Gender.Neuter -> "das"),
    articleForms = Map(
      "der" -> Gender.Masculine,
      "den" -> Gender.Masculine,
      "dem" -> Gender.Masculine,
      "des" -> Gender.Masculine,
      "die" -> Gender.Feminine,
      "das" -> Gender.Neuter,
    ),
    capitalizesNouns = true,
    declinedArticles = {
      val singular = (grammarCase: String, masculine: String, feminine: String, neuter: String) => {
        List(Gender.Masculine -> masculine, Gender.Feminine -> feminine, Gender.Neuter -> neuter).map {
          case (gender, article) => TaggedWord(Set(grammarCase, "singular"), Some(gender), article)
        }
      }
      val plural   = (grammarCase: String, article: String) => TaggedWord(Set(grammarCase, "plural"), None, article)
      singular("nominative", "der", "die", "das") ++
        singular("genitive", "des", "der", "des") ++
        singular("dative", "dem", "der", "dem") ++
        singular("accusative", "den", "die", "das") ++
        List(
          plural("nominative", "die"),
          plural("genitive", "der"),
          plural("dative", "den"),
          plural("accusative", "die"),
        )
    },
    subjectPronouns = persons("ich", "du", "er", "wir", "ihr", "sie"),
    subordinator = Some("dass"),
  )

  private val spanish: LanguageProfile = LanguageProfile(
    genders = List(Gender.Masculine, Gender.Feminine),
    definiteArticles = Map(Gender.Masculine -> "el", Gender.Feminine -> "la"),
    articleForms = Map(
      "el"  -> Gender.Masculine,
      "los" -> Gender.Masculine,
      "la"  -> Gender.Feminine,
      "las" -> Gender.Feminine,
    ),
    capitalizesNouns = false,
    reflexivePronouns = Set("me", "te", "se", "nos", "os"),
    declinedArticles = byNumber("el", "la", "los", "las"),
    singularArticleGender = spanishStressedA.map(_ -> Gender.Masculine).toMap,
    subjectPronouns = TaggedWord(Set("second-person", "singular", "vos-form"), None, "vos") ::
      persons("yo", "tú", "él", "nosotros", "vosotros", "ellos"),
  )

  /** `les` is left out of [[LanguageProfile.articleForms]]: it is the plural of both genders, so it names none. `l'` is
    * left out too: it joins the noun with no space (`l'homme`), and [[LanguageProfile.strip]] splits on a space.
    */
  private val french: LanguageProfile = LanguageProfile(
    genders = List(Gender.Masculine, Gender.Feminine),
    definiteArticles = Map(Gender.Masculine -> "le", Gender.Feminine -> "la"),
    articleForms = Map(
      "le" -> Gender.Masculine,
      "la" -> Gender.Feminine,
    ),
    capitalizesNouns = false,
    reflexivePronouns = Set("me", "te", "se", "nous", "vous"),
    declinedArticles = byNumber("le", "la", "les", "les"),
    subjectPronouns = persons("je", "tu", "il", "nous", "vous", "ils"),
    elisions = Map("je" -> "j'", "le" -> "l'", "la" -> "l'"),
    keepsPrefix = frenchAspiratedH,
  )

  /** The reflexive pronouns are the ones written before the verb (`me lavo`). A pronoun joined after it with a hyphen
    * (`lavo-me`) is not one token of its own, so the import does not tag that form `reflexive`.
    */
  private val portuguese: LanguageProfile = LanguageProfile(
    genders = List(Gender.Masculine, Gender.Feminine),
    definiteArticles = Map(Gender.Masculine -> "o", Gender.Feminine -> "a"),
    articleForms = Map(
      "o"  -> Gender.Masculine,
      "os" -> Gender.Masculine,
      "a"  -> Gender.Feminine,
      "as" -> Gender.Feminine,
    ),
    capitalizesNouns = false,
    reflexivePronouns = Set("me", "te", "se", "nos", "vos"),
    declinedArticles = byNumber("o", "a", "os", "as"),
    subjectPronouns = persons("eu", "tu", "ele", "nós", "vós", "eles"),
  )

  def of(language: WordLanguage): LanguageProfile = {
    language match {
      case WordLanguage.De =>
        german
      case WordLanguage.Es =>
        spanish
      case WordLanguage.Fr =>
        french
      case WordLanguage.Pt =>
        portuguese
      case WordLanguage.Hu =>
        hungarian
      case WordLanguage.En =>
        ungendered
    }
  }
}
