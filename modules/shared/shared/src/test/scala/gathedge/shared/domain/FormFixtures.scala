package gathedge.shared.domain

/** Real `word_forms` rows of a few words, lifted from a full import (`DictionaryImport --export` at no frequency cut).
  * Each line is `form;part of speech;gender;relation`, in the export's order. A gender counterpart or a diminutive goes
  * to `word_links` at import, so it is left out here too.
  */
object FormFixtures {

  val deKunstler: String = {
    """Künstler;noun;masculine;accusative,definite,plural
       |Künstler;noun;masculine;accusative,singular
       |Künstler;noun;masculine;dative,singular
       |Künstler;noun;masculine;definite,genitive,plural
       |Künstler;noun;masculine;definite,nominative,plural
       |Künstler;noun;masculine;nominative,singular
       |Künstler;noun;masculine;plural
       |Künstlern;noun;;dative,definite,plural
       |Künstlers;noun;;genitive
       |Künstlers;noun;;genitive,singular""".stripMargin
  }

  val deHaus: String = {
    """Haus;noun;neuter;accusative,singular
       |Haus;noun;neuter;dative,singular
       |Haus;noun;neuter;nominative,singular
       |Hause;noun;;dative,singular
       |Hauses;noun;;genitive
       |Hauses;noun;;genitive,singular
       |Häuser;noun;;accusative,definite,plural
       |Häuser;noun;;definite,genitive,plural
       |Häuser;noun;;definite,nominative,plural
       |Häuser;noun;;plural
       |Häusern;noun;;dative,definite,plural""".stripMargin
  }

  val deKaufen: String = {
    """gekauft haben werden;verb;;future,future-ii,infinitive
       |gekauft;verb;;participle,past
       |habe gekauft;verb;;first-person,indicative,perfect,singular
       |habe gekauft;verb;;first-person,perfect,singular,subjunctive
       |habe gekauft;verb;;perfect,singular,subjunctive,third-person
       |haben gekauft;verb;;first-person,indicative,perfect,plural
       |haben gekauft;verb;;first-person,perfect,plural,subjunctive
       |haben gekauft;verb;;indicative,perfect,plural,third-person
       |haben gekauft;verb;;perfect,plural,subjunctive,third-person
       |habest gekauft;verb;;perfect,second-person,singular,subjunctive
       |habet gekauft;verb;;perfect,plural,second-person,subjunctive
       |habt gekauft;verb;;indicative,perfect,plural,second-person
       |hast gekauft;verb;;indicative,perfect,second-person,singular
       |hat gekauft;verb;;indicative,perfect,singular,third-person
       |hatte gekauft;verb;;first-person,indicative,pluperfect,singular
       |hatte gekauft;verb;;indicative,pluperfect,singular,third-person
       |hatten gekauft;verb;;first-person,indicative,pluperfect,plural
       |hatten gekauft;verb;;indicative,pluperfect,plural,third-person
       |hattest gekauft;verb;;indicative,pluperfect,second-person,singular
       |hattet gekauft;verb;;indicative,pluperfect,plural,second-person
       |hätte gekauft;verb;;first-person,pluperfect,singular,subjunctive
       |hätte gekauft;verb;;pluperfect,singular,subjunctive,third-person
       |hätten gekauft;verb;;first-person,pluperfect,plural,subjunctive
       |hätten gekauft;verb;;pluperfect,plural,subjunctive,third-person
       |hättest gekauft;verb;;pluperfect,second-person,singular,subjunctive
       |hättet gekauft;verb;;pluperfect,plural,second-person,subjunctive
       |kauf;verb;;imperative,second-person,singular
       |kauf;verb;;imperative,singular
       |kaufe;verb;;first-person,indicative,present,singular
       |kaufe;verb;;first-person,present,singular
       |kaufe;verb;;first-person,singular,subjunctive,subjunctive-i
       |kaufe;verb;;first-person,singular,subjunctive-i,third-person
       |kaufe;verb;;imperative,second-person,singular
       |kaufe;verb;;imperative,singular
       |kaufe;verb;;singular,subjunctive,subjunctive-i,third-person
       |kaufen werden;verb;;future,future-i,infinitive
       |kaufen;verb;;first-person,indicative,plural,present
       |kaufen;verb;;first-person,plural,subjunctive,subjunctive-i
       |kaufen;verb;;indicative,plural,present,third-person
       |kaufen;verb;;infinitive
       |kaufen;verb;;plural,subjunctive,subjunctive-i,third-person
       |kaufend;verb;;participle,present
       |kaufest;verb;;second-person,singular,subjunctive,subjunctive-i
       |kaufest;verb;;second-person,singular,subjunctive-i
       |kaufet;verb;;plural,second-person,subjunctive,subjunctive-i
       |kaufet;verb;;plural,second-person,subjunctive-i
       |kaufst;verb;;indicative,present,second-person,singular
       |kaufst;verb;;present,second-person,singular
       |kauft;verb;;imperative,plural
       |kauft;verb;;imperative,plural,second-person
       |kauft;verb;;indicative,plural,present,second-person
       |kauft;verb;;indicative,present,singular,third-person
       |kauft;verb;;plural,present,second-person
       |kauft;verb;;present,singular,third-person
       |kaufte;verb;;first-person,formal,rare,singular,subjunctive,subjunctive-ii
       |kaufte;verb;;first-person,indicative,preterite,singular
       |kaufte;verb;;first-person,preterite,singular,third-person
       |kaufte;verb;;first-person,singular,subjunctive-ii,third-person
       |kaufte;verb;;formal,rare,singular,subjunctive,subjunctive-ii,third-person
       |kaufte;verb;;indicative,preterite,singular,third-person
       |kaufte;verb;;past
       |kauften;verb;;first-person,formal,plural,rare,subjunctive,subjunctive-ii
       |kauften;verb;;first-person,indicative,plural,preterite
       |kauften;verb;;first-person,plural,preterite,third-person
       |kauften;verb;;first-person,plural,subjunctive-ii,third-person
       |kauften;verb;;formal,plural,rare,subjunctive,subjunctive-ii,third-person
       |kauften;verb;;indicative,plural,preterite,third-person
       |kauftest;verb;;formal,rare,second-person,singular,subjunctive,subjunctive-ii
       |kauftest;verb;;indicative,preterite,second-person,singular
       |kauftest;verb;;preterite,second-person,singular
       |kauftest;verb;;second-person,singular,subjunctive-ii
       |kauftet;verb;;formal,plural,rare,second-person,subjunctive,subjunctive-ii
       |kauftet;verb;;indicative,plural,preterite,second-person
       |kauftet;verb;;plural,preterite,second-person
       |kauftet;verb;;plural,second-person,subjunctive-ii
       |werde gekauft haben;verb;;first-person,future,future-ii,indicative,singular
       |werde gekauft haben;verb;;first-person,future,future-ii,singular,subjunctive,subjunctive-i
       |werde gekauft haben;verb;;future,future-ii,singular,subjunctive,subjunctive-i,third-person
       |werde kaufen;verb;;first-person,future,future-i,indicative,singular
       |werde kaufen;verb;;first-person,future,future-i,singular,subjunctive,subjunctive-i
       |werde kaufen;verb;;future,future-i,singular,subjunctive,subjunctive-i,third-person
       |werden gekauft haben;verb;;first-person,future,future-ii,indicative,plural
       |werden gekauft haben;verb;;first-person,future,future-ii,plural,subjunctive,subjunctive-i
       |werden gekauft haben;verb;;future,future-ii,indicative,plural,third-person
       |werden gekauft haben;verb;;future,future-ii,plural,subjunctive,subjunctive-i,third-person
       |werden kaufen;verb;;first-person,future,future-i,indicative,plural
       |werden kaufen;verb;;first-person,future,future-i,plural,subjunctive,subjunctive-i
       |werden kaufen;verb;;future,future-i,indicative,plural,third-person
       |werden kaufen;verb;;future,future-i,plural,subjunctive,subjunctive-i,third-person
       |werdest gekauft haben;verb;;future,future-ii,second-person,singular,subjunctive,subjunctive-i
       |werdest kaufen;verb;;future,future-i,second-person,singular,subjunctive,subjunctive-i
       |werdet gekauft haben;verb;;future,future-ii,indicative,plural,second-person
       |werdet gekauft haben;verb;;future,future-ii,plural,second-person,subjunctive,subjunctive-i
       |werdet kaufen;verb;;future,future-i,indicative,plural,second-person
       |werdet kaufen;verb;;future,future-i,plural,second-person,subjunctive,subjunctive-i
       |wird gekauft haben;verb;;future,future-ii,indicative,singular,third-person
       |wird kaufen;verb;;future,future-i,indicative,singular,third-person
       |wirst gekauft haben;verb;;future,future-ii,indicative,second-person,singular
       |wirst kaufen;verb;;future,future-i,indicative,second-person,singular
       |würde gekauft haben;verb;;first-person,future,future-ii,singular,subjunctive,subjunctive-ii
       |würde gekauft haben;verb;;future,future-ii,singular,subjunctive,subjunctive-ii,third-person
       |würde kaufen;verb;;first-person,future,future-i,singular,subjunctive,subjunctive-ii
       |würde kaufen;verb;;future,future-i,singular,subjunctive,subjunctive-ii,third-person
       |würden gekauft haben;verb;;first-person,future,future-ii,plural,subjunctive,subjunctive-ii
       |würden gekauft haben;verb;;future,future-ii,plural,subjunctive,subjunctive-ii,third-person
       |würden kaufen;verb;;first-person,future,future-i,plural,subjunctive,subjunctive-ii
       |würden kaufen;verb;;future,future-i,plural,subjunctive,subjunctive-ii,third-person
       |würdest gekauft haben;verb;;future,future-ii,second-person,singular,subjunctive,subjunctive-ii
       |würdest kaufen;verb;;future,future-i,second-person,singular,subjunctive,subjunctive-ii
       |würdet gekauft haben;verb;;future,future-ii,plural,second-person,subjunctive,subjunctive-ii
       |würdet kaufen;verb;;future,future-i,plural,second-person,subjunctive,subjunctive-ii""".stripMargin
  }

  val deEinkaufen: String = {
    """eingekauft haben werden;verb;;future,future-ii,infinitive
       |eingekauft;verb;;participle,past
       |einkaufe;verb;;dependent,first-person,present,singular
       |einkaufe;verb;;dependent,first-person,singular,subjunctive-i,third-person
       |einkaufe;verb;;first-person,indicative,present,singular,subordinate-clause
       |einkaufe;verb;;first-person,singular,subjunctive,subjunctive-i,subordinate-clause
       |einkaufe;verb;;singular,subjunctive,subjunctive-i,subordinate-clause,third-person
       |einkaufen werden;verb;;future,future-i,infinitive
       |einkaufen;verb;;first-person,indicative,plural,present,subordinate-clause
       |einkaufen;verb;;first-person,plural,subjunctive,subjunctive-i,subordinate-clause
       |einkaufen;verb;;indicative,plural,present,subordinate-clause,third-person
       |einkaufen;verb;;infinitive
       |einkaufen;verb;;plural,subjunctive,subjunctive-i,subordinate-clause,third-person
       |einkaufend;verb;;participle,present
       |einkaufest;verb;;dependent,second-person,singular,subjunctive-i
       |einkaufest;verb;;second-person,singular,subjunctive,subjunctive-i,subordinate-clause
       |einkaufet;verb;;dependent,plural,second-person,subjunctive-i
       |einkaufet;verb;;plural,second-person,subjunctive,subjunctive-i,subordinate-clause
       |einkaufst;verb;;dependent,present,second-person,singular
       |einkaufst;verb;;indicative,present,second-person,singular,subordinate-clause
       |einkauft;verb;;dependent,plural,present,second-person
       |einkauft;verb;;dependent,present,singular,third-person
       |einkauft;verb;;indicative,plural,present,second-person,subordinate-clause
       |einkauft;verb;;indicative,present,singular,subordinate-clause,third-person
       |einkaufte;verb;;dependent,first-person,preterite,singular,third-person
       |einkaufte;verb;;dependent,first-person,singular,subjunctive-ii,third-person
       |einkaufte;verb;;first-person,formal,rare,singular,subjunctive,subjunctive-ii,subordinate-clause
       |einkaufte;verb;;first-person,indicative,preterite,singular,subordinate-clause
       |einkaufte;verb;;formal,rare,singular,subjunctive,subjunctive-ii,subordinate-clause,third-person
       |einkaufte;verb;;indicative,preterite,singular,subordinate-clause,third-person
       |einkauften;verb;;dependent,first-person,plural,preterite,third-person
       |einkauften;verb;;dependent,first-person,plural,subjunctive-ii,third-person
       |einkauften;verb;;first-person,formal,plural,rare,subjunctive,subjunctive-ii,subordinate-clause
       |einkauften;verb;;first-person,indicative,plural,preterite,subordinate-clause
       |einkauften;verb;;formal,plural,rare,subjunctive,subjunctive-ii,subordinate-clause,third-person
       |einkauften;verb;;indicative,plural,preterite,subordinate-clause,third-person
       |einkauftest;verb;;dependent,preterite,second-person,singular
       |einkauftest;verb;;dependent,second-person,singular,subjunctive-ii
       |einkauftest;verb;;formal,rare,second-person,singular,subjunctive,subjunctive-ii,subordinate-clause
       |einkauftest;verb;;indicative,preterite,second-person,singular,subordinate-clause
       |einkauftet;verb;;dependent,plural,preterite,second-person
       |einkauftet;verb;;dependent,plural,second-person,subjunctive-ii
       |einkauftet;verb;;formal,plural,rare,second-person,subjunctive,subjunctive-ii,subordinate-clause
       |einkauftet;verb;;indicative,plural,preterite,second-person,subordinate-clause
       |einzukaufen;verb;;infinitive,infinitive-zu
       |habe eingekauft;verb;;first-person,indicative,perfect,singular
       |habe eingekauft;verb;;first-person,perfect,singular,subjunctive
       |habe eingekauft;verb;;perfect,singular,subjunctive,third-person
       |haben eingekauft;verb;;first-person,indicative,perfect,plural
       |haben eingekauft;verb;;first-person,perfect,plural,subjunctive
       |haben eingekauft;verb;;indicative,perfect,plural,third-person
       |haben eingekauft;verb;;perfect,plural,subjunctive,third-person
       |habest eingekauft;verb;;perfect,second-person,singular,subjunctive
       |habet eingekauft;verb;;perfect,plural,second-person,subjunctive
       |habt eingekauft;verb;;indicative,perfect,plural,second-person
       |hast eingekauft;verb;;indicative,perfect,second-person,singular
       |hat eingekauft;verb;;indicative,perfect,singular,third-person
       |hatte eingekauft;verb;;first-person,indicative,pluperfect,singular
       |hatte eingekauft;verb;;indicative,pluperfect,singular,third-person
       |hatten eingekauft;verb;;first-person,indicative,pluperfect,plural
       |hatten eingekauft;verb;;indicative,pluperfect,plural,third-person
       |hattest eingekauft;verb;;indicative,pluperfect,second-person,singular
       |hattet eingekauft;verb;;indicative,pluperfect,plural,second-person
       |hätte eingekauft;verb;;first-person,pluperfect,singular,subjunctive
       |hätte eingekauft;verb;;pluperfect,singular,subjunctive,third-person
       |hätten eingekauft;verb;;first-person,pluperfect,plural,subjunctive
       |hätten eingekauft;verb;;pluperfect,plural,subjunctive,third-person
       |hättest eingekauft;verb;;pluperfect,second-person,singular,subjunctive
       |hättet eingekauft;verb;;pluperfect,plural,second-person,subjunctive
       |kauf ein;verb;;imperative,second-person,singular
       |kaufe ein;verb;;first-person,indicative,present,singular
       |kaufe ein;verb;;first-person,singular,subjunctive,subjunctive-i
       |kaufe ein;verb;;imperative,second-person,singular
       |kaufe ein;verb;;singular,subjunctive,subjunctive-i,third-person
       |kaufen ein;verb;;first-person,indicative,plural,present
       |kaufen ein;verb;;first-person,plural,subjunctive,subjunctive-i
       |kaufen ein;verb;;indicative,plural,present,third-person
       |kaufen ein;verb;;plural,subjunctive,subjunctive-i,third-person
       |kaufest ein;verb;;second-person,singular,subjunctive,subjunctive-i
       |kaufet ein;verb;;plural,second-person,subjunctive,subjunctive-i
       |kaufst ein;verb;;indicative,present,second-person,singular
       |kauft ein;verb;;imperative,plural,second-person
       |kauft ein;verb;;indicative,plural,present,second-person
       |kauft ein;verb;;indicative,present,singular,third-person
       |kauft ein;verb;;present,singular,third-person
       |kaufte ein;verb;;first-person,formal,rare,singular,subjunctive,subjunctive-ii
       |kaufte ein;verb;;first-person,indicative,preterite,singular
       |kaufte ein;verb;;formal,rare,singular,subjunctive,subjunctive-ii,third-person
       |kaufte ein;verb;;indicative,preterite,singular,third-person
       |kaufte ein;verb;;past
       |kauften ein;verb;;first-person,formal,plural,rare,subjunctive,subjunctive-ii
       |kauften ein;verb;;first-person,indicative,plural,preterite
       |kauften ein;verb;;formal,plural,rare,subjunctive,subjunctive-ii,third-person
       |kauften ein;verb;;indicative,plural,preterite,third-person
       |kauftest ein;verb;;formal,rare,second-person,singular,subjunctive,subjunctive-ii
       |kauftest ein;verb;;indicative,preterite,second-person,singular
       |kauftet ein;verb;;formal,plural,rare,second-person,subjunctive,subjunctive-ii
       |kauftet ein;verb;;indicative,plural,preterite,second-person
       |werde eingekauft haben;verb;;first-person,future,future-ii,indicative,singular
       |werde eingekauft haben;verb;;first-person,future,future-ii,singular,subjunctive,subjunctive-i
       |werde eingekauft haben;verb;;future,future-ii,singular,subjunctive,subjunctive-i,third-person
       |werde einkaufen;verb;;first-person,future,future-i,indicative,singular
       |werde einkaufen;verb;;first-person,future,future-i,singular,subjunctive,subjunctive-i
       |werde einkaufen;verb;;future,future-i,singular,subjunctive,subjunctive-i,third-person
       |werden eingekauft haben;verb;;first-person,future,future-ii,indicative,plural
       |werden eingekauft haben;verb;;first-person,future,future-ii,plural,subjunctive,subjunctive-i
       |werden eingekauft haben;verb;;future,future-ii,indicative,plural,third-person
       |werden eingekauft haben;verb;;future,future-ii,plural,subjunctive,subjunctive-i,third-person
       |werden einkaufen;verb;;first-person,future,future-i,indicative,plural
       |werden einkaufen;verb;;first-person,future,future-i,plural,subjunctive,subjunctive-i
       |werden einkaufen;verb;;future,future-i,indicative,plural,third-person
       |werden einkaufen;verb;;future,future-i,plural,subjunctive,subjunctive-i,third-person
       |werdest eingekauft haben;verb;;future,future-ii,second-person,singular,subjunctive,subjunctive-i
       |werdest einkaufen;verb;;future,future-i,second-person,singular,subjunctive,subjunctive-i
       |werdet eingekauft haben;verb;;future,future-ii,indicative,plural,second-person
       |werdet eingekauft haben;verb;;future,future-ii,plural,second-person,subjunctive,subjunctive-i
       |werdet einkaufen;verb;;future,future-i,indicative,plural,second-person
       |werdet einkaufen;verb;;future,future-i,plural,second-person,subjunctive,subjunctive-i
       |wird eingekauft haben;verb;;future,future-ii,indicative,singular,third-person
       |wird einkaufen;verb;;future,future-i,indicative,singular,third-person
       |wirst eingekauft haben;verb;;future,future-ii,indicative,second-person,singular
       |wirst einkaufen;verb;;future,future-i,indicative,second-person,singular
       |würde eingekauft haben;verb;;first-person,future,future-ii,singular,subjunctive,subjunctive-ii
       |würde eingekauft haben;verb;;future,future-ii,singular,subjunctive,subjunctive-ii,third-person
       |würde einkaufen;verb;;first-person,future,future-i,singular,subjunctive,subjunctive-ii
       |würde einkaufen;verb;;future,future-i,singular,subjunctive,subjunctive-ii,third-person
       |würden eingekauft haben;verb;;first-person,future,future-ii,plural,subjunctive,subjunctive-ii
       |würden eingekauft haben;verb;;future,future-ii,plural,subjunctive,subjunctive-ii,third-person
       |würden einkaufen;verb;;first-person,future,future-i,plural,subjunctive,subjunctive-ii
       |würden einkaufen;verb;;future,future-i,plural,subjunctive,subjunctive-ii,third-person
       |würdest eingekauft haben;verb;;future,future-ii,second-person,singular,subjunctive,subjunctive-ii
       |würdest einkaufen;verb;;future,future-i,second-person,singular,subjunctive,subjunctive-ii
       |würdet eingekauft haben;verb;;future,future-ii,plural,second-person,subjunctive,subjunctive-ii
       |würdet einkaufen;verb;;future,future-i,plural,second-person,subjunctive,subjunctive-ii""".stripMargin
  }

  val deFrei: String = {
    """am freiesten;adjective;;superlative
       |am freisten;adjective;;superlative
       |das freie;adjective;;accusative,definite,includes-article,neuter,singular,weak
       |das freie;adjective;;definite,includes-article,neuter,nominative,singular,weak
       |das freiere;adjective;;accusative,comparative,definite,includes-article,neuter,singular,weak
       |das freiere;adjective;;comparative,definite,includes-article,neuter,nominative,singular,weak
       |das freieste;adjective;;accusative,definite,includes-article,neuter,singular,superlative,weak
       |das freieste;adjective;;definite,includes-article,neuter,nominative,singular,superlative,weak
       |das freiste;adjective;;accusative,definite,includes-article,neuter,singular,superlative,weak
       |das freiste;adjective;;definite,includes-article,neuter,nominative,singular,superlative,weak
       |dem freien;adjective;;dative,definite,includes-article,masculine,singular,weak
       |dem freien;adjective;;dative,definite,includes-article,neuter,singular,weak
       |dem freieren;adjective;;comparative,dative,definite,includes-article,masculine,singular,weak
       |dem freieren;adjective;;comparative,dative,definite,includes-article,neuter,singular,weak
       |dem freiesten;adjective;;dative,definite,includes-article,masculine,singular,superlative,weak
       |dem freiesten;adjective;;dative,definite,includes-article,neuter,singular,superlative,weak
       |dem freisten;adjective;;dative,definite,includes-article,masculine,singular,superlative,weak
       |dem freisten;adjective;;dative,definite,includes-article,neuter,singular,superlative,weak
       |den freien;adjective;;accusative,definite,includes-article,masculine,singular,weak
       |den freien;adjective;;dative,definite,includes-article,plural,weak
       |den freieren;adjective;;accusative,comparative,definite,includes-article,masculine,singular,weak
       |den freieren;adjective;;comparative,dative,definite,includes-article,plural,weak
       |den freiesten;adjective;;accusative,definite,includes-article,masculine,singular,superlative,weak
       |den freiesten;adjective;;dative,definite,includes-article,plural,superlative,weak
       |den freisten;adjective;;accusative,definite,includes-article,masculine,singular,superlative,weak
       |den freisten;adjective;;dative,definite,includes-article,plural,superlative,weak
       |der freie;adjective;;definite,includes-article,masculine,nominative,singular,weak
       |der freien;adjective;;dative,definite,feminine,includes-article,singular,weak
       |der freien;adjective;;definite,feminine,genitive,includes-article,singular,weak
       |der freien;adjective;;definite,genitive,includes-article,plural,weak
       |der freiere;adjective;;comparative,definite,includes-article,masculine,nominative,singular,weak
       |der freieren;adjective;;comparative,dative,definite,feminine,includes-article,singular,weak
       |der freieren;adjective;;comparative,definite,feminine,genitive,includes-article,singular,weak
       |der freieren;adjective;;comparative,definite,genitive,includes-article,plural,weak
       |der freieste;adjective;;definite,includes-article,masculine,nominative,singular,superlative,weak
       |der freiesten;adjective;;dative,definite,feminine,includes-article,singular,superlative,weak
       |der freiesten;adjective;;definite,feminine,genitive,includes-article,singular,superlative,weak
       |der freiesten;adjective;;definite,genitive,includes-article,plural,superlative,weak
       |der freiste;adjective;;definite,includes-article,masculine,nominative,singular,superlative,weak
       |der freisten;adjective;;dative,definite,feminine,includes-article,singular,superlative,weak
       |der freisten;adjective;;definite,feminine,genitive,includes-article,singular,superlative,weak
       |der freisten;adjective;;definite,genitive,includes-article,plural,superlative,weak
       |des freien;adjective;;definite,genitive,includes-article,masculine,singular,weak
       |des freien;adjective;;definite,genitive,includes-article,neuter,singular,weak
       |des freieren;adjective;;comparative,definite,genitive,includes-article,masculine,singular,weak
       |des freieren;adjective;;comparative,definite,genitive,includes-article,neuter,singular,weak
       |des freiesten;adjective;;definite,genitive,includes-article,masculine,singular,superlative,weak
       |des freiesten;adjective;;definite,genitive,includes-article,neuter,singular,superlative,weak
       |des freisten;adjective;;definite,genitive,includes-article,masculine,singular,superlative,weak
       |des freisten;adjective;;definite,genitive,includes-article,neuter,singular,superlative,weak
       |die freie;adjective;;accusative,definite,feminine,includes-article,singular,weak
       |die freie;adjective;;definite,feminine,includes-article,nominative,singular,weak
       |die freien;adjective;;accusative,definite,includes-article,plural,weak
       |die freien;adjective;;definite,includes-article,nominative,plural,weak
       |die freiere;adjective;;accusative,comparative,definite,feminine,includes-article,singular,weak
       |die freiere;adjective;;comparative,definite,feminine,includes-article,nominative,singular,weak
       |die freieren;adjective;;accusative,comparative,definite,includes-article,plural,weak
       |die freieren;adjective;;comparative,definite,includes-article,nominative,plural,weak
       |die freieste;adjective;;accusative,definite,feminine,includes-article,singular,superlative,weak
       |die freieste;adjective;;definite,feminine,includes-article,nominative,singular,superlative,weak
       |die freiesten;adjective;;accusative,definite,includes-article,plural,superlative,weak
       |die freiesten;adjective;;definite,includes-article,nominative,plural,superlative,weak
       |die freiste;adjective;;accusative,definite,feminine,includes-article,singular,superlative,weak
       |die freiste;adjective;;definite,feminine,includes-article,nominative,singular,superlative,weak
       |die freisten;adjective;;accusative,definite,includes-article,plural,superlative,weak
       |die freisten;adjective;;definite,includes-article,nominative,plural,superlative,weak
       |ein freier;adjective;;includes-article,indefinite,masculine,mixed,nominative,singular
       |ein freierer;adjective;;comparative,includes-article,indefinite,masculine,mixed,nominative,singular
       |ein freieres;adjective;;accusative,comparative,includes-article,indefinite,mixed,neuter,singular
       |ein freieres;adjective;;comparative,includes-article,indefinite,mixed,neuter,nominative,singular
       |ein freies;adjective;;accusative,includes-article,indefinite,mixed,neuter,singular
       |ein freies;adjective;;includes-article,indefinite,mixed,neuter,nominative,singular
       |ein freiester;adjective;;includes-article,indefinite,masculine,mixed,nominative,singular,superlative
       |ein freiestes;adjective;;accusative,includes-article,indefinite,mixed,neuter,singular,superlative
       |ein freiestes;adjective;;includes-article,indefinite,mixed,neuter,nominative,singular,superlative
       |ein freister;adjective;;includes-article,indefinite,masculine,mixed,nominative,singular,superlative
       |ein freistes;adjective;;accusative,includes-article,indefinite,mixed,neuter,singular,superlative
       |ein freistes;adjective;;includes-article,indefinite,mixed,neuter,nominative,singular,superlative
       |eine freie;adjective;;accusative,feminine,includes-article,indefinite,mixed,singular
       |eine freie;adjective;;feminine,includes-article,indefinite,mixed,nominative,singular
       |eine freiere;adjective;;accusative,comparative,feminine,includes-article,indefinite,mixed,singular
       |eine freiere;adjective;;comparative,feminine,includes-article,indefinite,mixed,nominative,singular
       |eine freieste;adjective;;accusative,feminine,includes-article,indefinite,mixed,singular,superlative
       |eine freieste;adjective;;feminine,includes-article,indefinite,mixed,nominative,singular,superlative
       |eine freiste;adjective;;accusative,feminine,includes-article,indefinite,mixed,singular,superlative
       |eine freiste;adjective;;feminine,includes-article,indefinite,mixed,nominative,singular,superlative
       |einem freien;adjective;;dative,includes-article,indefinite,masculine,mixed,singular
       |einem freien;adjective;;dative,includes-article,indefinite,mixed,neuter,singular
       |einem freieren;adjective;;comparative,dative,includes-article,indefinite,masculine,mixed,singular
       |einem freieren;adjective;;comparative,dative,includes-article,indefinite,mixed,neuter,singular
       |einem freiesten;adjective;;dative,includes-article,indefinite,masculine,mixed,singular,superlative
       |einem freiesten;adjective;;dative,includes-article,indefinite,mixed,neuter,singular,superlative
       |einem freisten;adjective;;dative,includes-article,indefinite,masculine,mixed,singular,superlative
       |einem freisten;adjective;;dative,includes-article,indefinite,mixed,neuter,singular,superlative
       |einen freien;adjective;;accusative,includes-article,indefinite,masculine,mixed,singular
       |einen freieren;adjective;;accusative,comparative,includes-article,indefinite,masculine,mixed,singular
       |einen freiesten;adjective;;accusative,includes-article,indefinite,masculine,mixed,singular,superlative
       |einen freisten;adjective;;accusative,includes-article,indefinite,masculine,mixed,singular,superlative
       |einer freien;adjective;;dative,feminine,includes-article,indefinite,mixed,singular
       |einer freien;adjective;;feminine,genitive,includes-article,indefinite,mixed,singular
       |einer freieren;adjective;;comparative,dative,feminine,includes-article,indefinite,mixed,singular
       |einer freieren;adjective;;comparative,feminine,genitive,includes-article,indefinite,mixed,singular
       |einer freiesten;adjective;;dative,feminine,includes-article,indefinite,mixed,singular,superlative
       |einer freiesten;adjective;;feminine,genitive,includes-article,indefinite,mixed,singular,superlative
       |einer freisten;adjective;;dative,feminine,includes-article,indefinite,mixed,singular,superlative
       |einer freisten;adjective;;feminine,genitive,includes-article,indefinite,mixed,singular,superlative
       |eines freien;adjective;;genitive,includes-article,indefinite,masculine,mixed,singular
       |eines freien;adjective;;genitive,includes-article,indefinite,mixed,neuter,singular
       |eines freieren;adjective;;comparative,genitive,includes-article,indefinite,masculine,mixed,singular
       |eines freieren;adjective;;comparative,genitive,includes-article,indefinite,mixed,neuter,singular
       |eines freiesten;adjective;;genitive,includes-article,indefinite,masculine,mixed,singular,superlative
       |eines freiesten;adjective;;genitive,includes-article,indefinite,mixed,neuter,singular,superlative
       |eines freisten;adjective;;genitive,includes-article,indefinite,masculine,mixed,singular,superlative
       |eines freisten;adjective;;genitive,includes-article,indefinite,mixed,neuter,singular,superlative
       |frei;adjective;;feminine,predicative,singular
       |frei;adjective;;masculine,predicative,singular
       |frei;adjective;;neuter,predicative,singular
       |frei;adjective;;plural,predicative
       |freie;adjective;;accusative,feminine,mixed,nominative,singular,strong
       |freie;adjective;;accusative,feminine,neuter,singular,weak
       |freie;adjective;;accusative,feminine,singular,strong,without-article
       |freie;adjective;;accusative,nominative,plural,strong
       |freie;adjective;;accusative,plural,strong,without-article
       |freie;adjective;;feminine,nominative,singular,strong,without-article
       |freie;adjective;;nominative,plural,strong,without-article
       |freie;adjective;;nominative,singular,weak
       |freiem;adjective;;dative,masculine,neuter,singular,strong
       |freiem;adjective;;dative,masculine,singular,strong,without-article
       |freiem;adjective;;dative,neuter,singular,strong,without-article
       |freien;adjective;;accusative,indefinite,mixed,plural
       |freien;adjective;;accusative,masculine,mixed,singular,strong,weak
       |freien;adjective;;accusative,masculine,singular,strong,without-article
       |freien;adjective;;dative,genitive,mixed,singular,weak
       |freien;adjective;;dative,indefinite,mixed,plural
       |freien;adjective;;dative,plural,strong
       |freien;adjective;;dative,plural,strong,without-article
       |freien;adjective;;genitive,indefinite,mixed,plural
       |freien;adjective;;genitive,masculine,neuter,singular,strong
       |freien;adjective;;genitive,masculine,singular,strong,without-article
       |freien;adjective;;genitive,neuter,singular,strong,without-article
       |freien;adjective;;indefinite,mixed,nominative,plural
       |freien;adjective;;mixed,plural,weak
       |freier;adjective;;comparative
       |freier;adjective;;comparative,feminine,predicative,singular
       |freier;adjective;;comparative,masculine,predicative,singular
       |freier;adjective;;comparative,neuter,predicative,singular
       |freier;adjective;;comparative,plural,predicative
       |freier;adjective;;dative,feminine,genitive,singular,strong
       |freier;adjective;;dative,feminine,singular,strong,without-article
       |freier;adjective;;feminine,genitive,singular,strong,without-article
       |freier;adjective;;genitive,plural,strong
       |freier;adjective;;genitive,plural,strong,without-article
       |freier;adjective;;masculine,mixed,nominative,singular,strong
       |freier;adjective;;masculine,nominative,singular,strong
       |freier;adjective;;masculine,nominative,singular,strong,without-article
       |freiere;adjective;;accusative,comparative,feminine,mixed,nominative,singular,strong
       |freiere;adjective;;accusative,comparative,feminine,neuter,singular,weak
       |freiere;adjective;;accusative,comparative,feminine,singular,strong,without-article
       |freiere;adjective;;accusative,comparative,nominative,plural,strong
       |freiere;adjective;;accusative,comparative,plural,strong,without-article
       |freiere;adjective;;comparative,feminine,nominative,singular,strong,without-article
       |freiere;adjective;;comparative,nominative,plural,strong,without-article
       |freiere;adjective;;comparative,nominative,singular,weak
       |freierem;adjective;;comparative,dative,masculine,neuter,singular,strong
       |freierem;adjective;;comparative,dative,masculine,singular,strong,without-article
       |freierem;adjective;;comparative,dative,neuter,singular,strong,without-article
       |freieren;adjective;;accusative,comparative,indefinite,mixed,plural
       |freieren;adjective;;accusative,comparative,masculine,mixed,singular,strong,weak
       |freieren;adjective;;accusative,comparative,masculine,singular,strong,without-article
       |freieren;adjective;;comparative,dative,genitive,mixed,singular,weak
       |freieren;adjective;;comparative,dative,indefinite,mixed,plural
       |freieren;adjective;;comparative,dative,plural,strong
       |freieren;adjective;;comparative,dative,plural,strong,without-article
       |freieren;adjective;;comparative,genitive,indefinite,mixed,plural
       |freieren;adjective;;comparative,genitive,masculine,neuter,singular,strong
       |freieren;adjective;;comparative,genitive,masculine,singular,strong,without-article
       |freieren;adjective;;comparative,genitive,neuter,singular,strong,without-article
       |freieren;adjective;;comparative,indefinite,mixed,nominative,plural
       |freieren;adjective;;comparative,mixed,plural,weak
       |freierer;adjective;;comparative,dative,feminine,genitive,singular,strong
       |freierer;adjective;;comparative,dative,feminine,singular,strong,without-article
       |freierer;adjective;;comparative,feminine,genitive,singular,strong,without-article
       |freierer;adjective;;comparative,genitive,plural,strong
       |freierer;adjective;;comparative,genitive,plural,strong,without-article
       |freierer;adjective;;comparative,masculine,mixed,nominative,singular,strong
       |freierer;adjective;;comparative,masculine,nominative,singular,strong,without-article
       |freieres;adjective;;accusative,comparative,mixed,neuter,nominative,singular,strong
       |freieres;adjective;;accusative,comparative,neuter,singular,strong,without-article
       |freieres;adjective;;comparative,neuter,nominative,singular,strong,without-article
       |freies;adjective;;accusative,mixed,neuter,nominative,singular,strong
       |freies;adjective;;accusative,neuter,singular,strong,without-article
       |freies;adjective;;neuter,nominative,singular,strong,without-article
       |freieste;adjective;;accusative,feminine,singular,strong,superlative,without-article
       |freieste;adjective;;accusative,plural,strong,superlative,without-article
       |freieste;adjective;;feminine,nominative,singular,strong,superlative,without-article
       |freieste;adjective;;nominative,plural,strong,superlative,without-article
       |freiestem;adjective;;dative,masculine,singular,strong,superlative,without-article
       |freiestem;adjective;;dative,neuter,singular,strong,superlative,without-article
       |freiesten;adjective;;accusative,indefinite,mixed,plural,superlative
       |freiesten;adjective;;accusative,masculine,singular,strong,superlative,without-article
       |freiesten;adjective;;dative,indefinite,mixed,plural,superlative
       |freiesten;adjective;;dative,plural,strong,superlative,without-article
       |freiesten;adjective;;genitive,indefinite,mixed,plural,superlative
       |freiesten;adjective;;genitive,masculine,singular,strong,superlative,without-article
       |freiesten;adjective;;genitive,neuter,singular,strong,superlative,without-article
       |freiesten;adjective;;indefinite,mixed,nominative,plural,superlative
       |freiesten;adjective;;superlative
       |freiester;adjective;;dative,feminine,singular,strong,superlative,without-article
       |freiester;adjective;;feminine,genitive,singular,strong,superlative,without-article
       |freiester;adjective;;genitive,plural,strong,superlative,without-article
       |freiester;adjective;;masculine,nominative,singular,strong,superlative,without-article
       |freiestes;adjective;;accusative,neuter,singular,strong,superlative,without-article
       |freiestes;adjective;;neuter,nominative,singular,strong,superlative,without-article
       |freiste;adjective;;accusative,feminine,mixed,nominative,singular,strong,superlative
       |freiste;adjective;;accusative,feminine,neuter,singular,superlative,weak
       |freiste;adjective;;accusative,feminine,singular,strong,superlative,without-article
       |freiste;adjective;;accusative,nominative,plural,strong,superlative
       |freiste;adjective;;accusative,plural,strong,superlative,without-article
       |freiste;adjective;;feminine,nominative,singular,strong,superlative,without-article
       |freiste;adjective;;nominative,plural,strong,superlative,without-article
       |freiste;adjective;;nominative,singular,superlative,weak
       |freistem;adjective;;dative,masculine,neuter,singular,strong,superlative
       |freistem;adjective;;dative,masculine,singular,strong,superlative,without-article
       |freistem;adjective;;dative,neuter,singular,strong,superlative,without-article
       |freisten;adjective;;accusative,indefinite,mixed,plural,superlative
       |freisten;adjective;;accusative,masculine,mixed,singular,strong,superlative,weak
       |freisten;adjective;;accusative,masculine,singular,strong,superlative,without-article
       |freisten;adjective;;dative,genitive,mixed,singular,superlative,weak
       |freisten;adjective;;dative,indefinite,mixed,plural,superlative
       |freisten;adjective;;dative,plural,strong,superlative
       |freisten;adjective;;dative,plural,strong,superlative,without-article
       |freisten;adjective;;genitive,indefinite,mixed,plural,superlative
       |freisten;adjective;;genitive,masculine,neuter,singular,strong,superlative
       |freisten;adjective;;genitive,masculine,singular,strong,superlative,without-article
       |freisten;adjective;;genitive,neuter,singular,strong,superlative,without-article
       |freisten;adjective;;indefinite,mixed,nominative,plural,superlative
       |freisten;adjective;;mixed,plural,superlative,weak
       |freisten;adjective;;superlative
       |freister;adjective;;dative,feminine,genitive,singular,strong,superlative
       |freister;adjective;;dative,feminine,singular,strong,superlative,without-article
       |freister;adjective;;feminine,genitive,singular,strong,superlative,without-article
       |freister;adjective;;genitive,plural,strong,superlative
       |freister;adjective;;genitive,plural,strong,superlative,without-article
       |freister;adjective;;masculine,mixed,nominative,singular,strong,superlative
       |freister;adjective;;masculine,nominative,singular,strong,superlative,without-article
       |freistes;adjective;;accusative,mixed,neuter,nominative,singular,strong,superlative
       |freistes;adjective;;accusative,neuter,singular,strong,superlative,without-article
       |freistes;adjective;;neuter,nominative,singular,strong,superlative,without-article
       |keine freien;adjective;;accusative,includes-article,indefinite,mixed,negative,plural
       |keine freien;adjective;;includes-article,indefinite,mixed,negative,nominative,plural
       |keine freieren;adjective;;accusative,comparative,includes-article,indefinite,mixed,negative,plural
       |keine freieren;adjective;;comparative,includes-article,indefinite,mixed,negative,nominative,plural
       |keine freiesten;adjective;;accusative,includes-article,indefinite,mixed,negative,plural,superlative
       |keine freiesten;adjective;;includes-article,indefinite,mixed,negative,nominative,plural,superlative
       |keine freisten;adjective;;accusative,includes-article,indefinite,mixed,negative,plural,superlative
       |keine freisten;adjective;;includes-article,indefinite,mixed,negative,nominative,plural,superlative
       |keinen freien;adjective;;dative,includes-article,indefinite,mixed,negative,plural
       |keinen freieren;adjective;;comparative,dative,includes-article,indefinite,mixed,negative,plural
       |keinen freiesten;adjective;;dative,includes-article,indefinite,mixed,negative,plural,superlative
       |keinen freisten;adjective;;dative,includes-article,indefinite,mixed,negative,plural,superlative
       |keiner freien;adjective;;genitive,includes-article,indefinite,mixed,negative,plural
       |keiner freieren;adjective;;comparative,genitive,includes-article,indefinite,mixed,negative,plural
       |keiner freiesten;adjective;;genitive,includes-article,indefinite,mixed,negative,plural,superlative
       |keiner freisten;adjective;;genitive,includes-article,indefinite,mixed,negative,plural,superlative""".stripMargin
  }

  val huHaz: String = {
    """ház;noun;;nominative,singular
       |háza;noun;;possessed-single,possessive,singular,third-person
       |házad;noun;;possessed-single,possessive,second-person,singular
       |házai;noun;;possessed-many,possessive,singular,third-person
       |házaid;noun;;possessed-many,possessive,second-person,singular
       |házaik;noun;;plural,possessed-many,possessive,third-person
       |házaim;noun;;first-person,possessed-many,possessive,singular
       |házaink;noun;;first-person,plural,possessed-many,possessive
       |házaitok;noun;;plural,possessed-many,possessive,second-person
       |házak;noun;;nominative,plural
       |házak;noun;;plural
       |házakat;noun;;accusative,plural
       |házakba;noun;;illative,plural
       |házakban;noun;;inessive,plural
       |házakból;noun;;elative,plural
       |házakhoz;noun;;allative,plural
       |házakig;noun;;plural,terminative
       |házakkal;noun;;instrumental,plural
       |házakká;noun;;plural,translative
       |házakként;noun;;essive-formal,plural
       |házaknak;noun;;dative,plural
       |házaknál;noun;;adessive,plural
       |házakon;noun;;plural,superessive
       |házakra;noun;;plural,sublative
       |házakról;noun;;delative,plural
       |házaktól;noun;;ablative,plural
       |házaké;noun;;plural,possessed-single,possessor
       |házakéi;noun;;plural,possessed-many,possessor
       |házakért;noun;;causal-final,plural
       |házam;noun;;first-person,possessed-single,possessive,singular
       |házat;noun;;accusative,singular
       |házatok;noun;;plural,possessed-single,possessive,second-person
       |házba;noun;;illative,singular
       |házban;noun;;inessive,singular
       |házból;noun;;elative,singular
       |házhoz;noun;;allative,singular
       |házig;noun;;singular,terminative
       |házként;noun;;essive-formal,singular
       |háznak;noun;;dative,singular
       |háznál;noun;;adessive,singular
       |házon;noun;;singular,superessive
       |házra;noun;;singular,sublative
       |házról;noun;;delative,singular
       |háztól;noun;;ablative,singular
       |házuk;noun;;plural,possessed-single,possessive,third-person
       |házunk;noun;;first-person,plural,possessed-single,possessive
       |házzal;noun;;instrumental,singular
       |házzá;noun;;singular,translative
       |házé;noun;;possessed-single,possessor,singular
       |házé;noun;;possessive,predicative,singular
       |házéi;noun;;plural,possessive,predicative
       |házéi;noun;;possessed-many,possessor,singular
       |házért;noun;;causal-final,singular""".stripMargin
  }

  val huAd: String = {
    """ad;verb;;indefinite,indicative,present,singular,third-person
       |adandó;verb;;future,participle
       |adat;verb;;causative
       |adat;verb;;causative,transitive
       |adatik;verb;;intransitive,passive
       |add;verb;;definite,present,second-person,singular,subjunctive
       |adhasd;verb;;definite,potential,present,second-person,singular,subjunctive
       |adhass;verb;;indefinite,potential,present,second-person,singular,subjunctive
       |adhassa;verb;;definite,potential,present,singular,subjunctive,third-person
       |adhassad;verb;;definite,potential,present,second-person,singular,subjunctive
       |adhassak;verb;;first-person,indefinite,potential,present,singular,subjunctive
       |adhassalak;verb;;first-person,object-second-person,potential,present,singular,subjunctive
       |adhassam;verb;;definite,first-person,potential,present,singular,subjunctive
       |adhassanak;verb;;indefinite,plural,potential,present,subjunctive,third-person
       |adhassatok;verb;;indefinite,plural,potential,present,second-person,subjunctive
       |adhasson;verb;;indefinite,potential,present,singular,subjunctive,third-person
       |adhassuk;verb;;definite,first-person,plural,potential,present,subjunctive
       |adhassunk;verb;;first-person,indefinite,plural,potential,present,subjunctive
       |adhassák;verb;;definite,plural,potential,present,subjunctive,third-person
       |adhassál;verb;;indefinite,potential,present,second-person,singular,subjunctive
       |adhassátok;verb;;definite,plural,potential,present,second-person,subjunctive
       |adhat;verb;;indefinite,indicative,potential,present,singular,third-person
       |adhat;verb;;potential
       |adhatja;verb;;definite,indicative,potential,present,singular,third-person
       |adhatjuk;verb;;definite,first-person,indicative,plural,potential,present
       |adhatják;verb;;definite,indicative,plural,potential,present,third-person
       |adhatjátok;verb;;definite,indicative,plural,potential,present,second-person
       |adhatlak;verb;;first-person,indicative,object-second-person,potential,present,singular
       |adhatna;verb;;conditional,indefinite,potential,present,singular,third-person
       |adhatnak;verb;;indefinite,indicative,plural,potential,present,third-person
       |adhatni;verb;;infinitive,potential
       |adhatnia;verb;;infinitive,potential,singular,third-person
       |adhatniuk;verb;;infinitive,plural,potential,third-person
       |adhatnod;verb;;infinitive,potential,second-person,singular
       |adhatnom;verb;;first-person,infinitive,potential,singular
       |adhatnotok;verb;;infinitive,plural,potential,second-person
       |adhatnunk;verb;;first-person,infinitive,plural,potential
       |adhatná;verb;;conditional,definite,potential,present,singular,third-person
       |adhatnád;verb;;conditional,definite,potential,present,second-person,singular
       |adhatnák;verb;;conditional,definite,plural,potential,present,third-person
       |adhatnál;verb;;conditional,indefinite,potential,present,second-person,singular
       |adhatnálak;verb;;conditional,first-person,object-second-person,potential,present,singular
       |adhatnám;verb;;conditional,definite,first-person,potential,present,singular
       |adhatnának;verb;;conditional,indefinite,plural,potential,present,third-person
       |adhatnánk;verb;;conditional,definite,first-person,plural,potential,present
       |adhatnánk;verb;;conditional,first-person,indefinite,plural,potential,present
       |adhatnátok;verb;;conditional,definite,plural,potential,present,second-person
       |adhatnátok;verb;;conditional,indefinite,plural,potential,present,second-person
       |adhatnék;verb;;conditional,first-person,indefinite,potential,present,singular
       |adhatnók;verb;;conditional,definite,first-person,plural,potential,present
       |adhatod;verb;;definite,indicative,potential,present,second-person,singular
       |adhatok;verb;;first-person,indefinite,indicative,potential,present,singular
       |adhatom;verb;;definite,first-person,indicative,potential,present,singular
       |adhatott;verb;;indefinite,indicative,past,potential,singular,third-person
       |adhatsz;verb;;indefinite,indicative,potential,present,second-person,singular
       |adhatta;verb;;definite,indicative,past,potential,singular,third-person
       |adhattad;verb;;definite,indicative,past,potential,second-person,singular
       |adhattak;verb;;indefinite,indicative,past,plural,potential,third-person
       |adhattalak;verb;;first-person,indicative,object-second-person,past,potential,singular
       |adhattam;verb;;definite,first-person,indicative,past,potential,singular
       |adhattam;verb;;first-person,indefinite,indicative,past,potential,singular
       |adhattatok;verb;;indefinite,indicative,past,plural,potential,second-person
       |adhattok;verb;;indefinite,indicative,plural,potential,present,second-person
       |adhattuk;verb;;definite,first-person,indicative,past,plural,potential
       |adhattunk;verb;;first-person,indefinite,indicative,past,plural,potential
       |adhatták;verb;;definite,indicative,past,plural,potential,third-person
       |adhattál;verb;;indefinite,indicative,past,potential,second-person,singular
       |adhattátok;verb;;definite,indicative,past,plural,potential,second-person
       |adhatunk;verb;;first-person,indefinite,indicative,plural,potential,present
       |adj;verb;;indefinite,present,second-person,singular,subjunctive
       |adja;verb;;definite,indicative,present,singular,third-person
       |adja;verb;;definite,present,singular,subjunctive,third-person
       |adjak;verb;;first-person,indefinite,present,singular,subjunctive
       |adjalak;verb;;first-person,object-second-person,present,singular,subjunctive
       |adjam;verb;;definite,first-person,present,singular,subjunctive
       |adjanak;verb;;indefinite,plural,present,subjunctive,third-person
       |adjatok;verb;;indefinite,plural,present,second-person,subjunctive
       |adjon;verb;;indefinite,present,singular,subjunctive,third-person
       |adjuk;verb;;definite,first-person,indicative,plural,present
       |adjuk;verb;;definite,first-person,plural,present,subjunctive
       |adjunk;verb;;first-person,indefinite,plural,present,subjunctive
       |adják;verb;;definite,indicative,plural,present,third-person
       |adják;verb;;definite,plural,present,subjunctive,third-person
       |adjál;verb;;indefinite,present,second-person,singular,subjunctive
       |adjátok;verb;;definite,indicative,plural,present,second-person
       |adjátok;verb;;definite,plural,present,second-person,subjunctive
       |adlak;verb;;first-person,indicative,object-second-person,present,singular
       |adna;verb;;conditional,indefinite,present,singular,third-person
       |adnak;verb;;indefinite,indicative,plural,present,third-person
       |adni;verb;;infinitive
       |adnia;verb;;infinitive,singular,third-person
       |adniuk;verb;;infinitive,plural,third-person
       |adnod;verb;;infinitive,second-person,singular
       |adnom;verb;;first-person,infinitive,singular
       |adnotok;verb;;infinitive,plural,second-person
       |adnunk;verb;;first-person,infinitive,plural
       |adná;verb;;conditional,definite,present,singular,third-person
       |adnád;verb;;conditional,definite,present,second-person,singular
       |adnák;verb;;conditional,definite,plural,present,third-person
       |adnál;verb;;conditional,indefinite,present,second-person,singular
       |adnálak;verb;;conditional,first-person,object-second-person,present,singular
       |adnám;verb;;conditional,definite,first-person,present,singular
       |adnának;verb;;conditional,indefinite,plural,present,third-person
       |adnánk;verb;;conditional,definite,first-person,plural,present
       |adnánk;verb;;conditional,first-person,indefinite,plural,present
       |adnátok;verb;;conditional,definite,plural,present,second-person
       |adnátok;verb;;conditional,indefinite,plural,present,second-person
       |adnék;verb;;conditional,first-person,indefinite,present,singular
       |adnók;verb;;conditional,definite,first-person,plural,present
       |adod;verb;;definite,indicative,present,second-person,singular
       |adok;verb;;first-person,indefinite,indicative,present,singular
       |adom;verb;;definite,first-person,indicative,present,singular
       |adott;verb;;indefinite,indicative,past,singular,third-person
       |adott;verb;;participle,past
       |adsz;verb;;indefinite,indicative,present,second-person,singular
       |adta;verb;;definite,indicative,past,singular,third-person
       |adtad;verb;;definite,indicative,past,second-person,singular
       |adtak;verb;;indefinite,indicative,past,plural,third-person
       |adtalak;verb;;first-person,indicative,object-second-person,past,singular
       |adtam;verb;;definite,first-person,indicative,past,singular
       |adtam;verb;;first-person,indefinite,indicative,past,singular
       |adtatok;verb;;indefinite,indicative,past,plural,second-person
       |adtok;verb;;indefinite,indicative,plural,present,second-person
       |adtuk;verb;;definite,first-person,indicative,past,plural
       |adtunk;verb;;first-person,indefinite,indicative,past,plural
       |adták;verb;;definite,indicative,past,plural,third-person
       |adtál;verb;;indefinite,indicative,past,second-person,singular
       |adtátok;verb;;definite,indicative,past,plural,second-person
       |adunk;verb;;first-person,indefinite,indicative,plural,present
       |adva;verb;;adverbial,participle
       |adván;verb;;adverbial,participle
       |adás;verb;;noun-from-verb
       |adó;verb;;participle,present""".stripMargin
  }

  val esComprar: String = {
    """compra;verb;;imperative,informal,second-person,singular
       |compra;verb;;imperative,second-person,singular
       |compra;verb;;indicative,present,singular,third-person
       |compraba;verb;;first-person,imperfect,indicative,singular
       |compraba;verb;;first-person,imperfect,indicative,singular,third-person
       |compraba;verb;;imperfect,indicative,singular,third-person
       |comprabais;verb;;imperfect,indicative,plural,second-person
       |compraban;verb;;imperfect,indicative,plural,third-person
       |comprabas;verb;;imperfect,indicative,second-person,singular
       |comprad;verb;;imperative,plural,second-person
       |comprada;verb;;feminine,participle,past,singular
       |compradas;verb;;feminine,participle,past,plural
       |compradla;verb;;accusative,combined-form,imperative,informal,object-singular,object-third-person,plural,second-person
       |compradla;verb;;accusative,imperative,object-feminine,object-singular,object-third-person,plural,second-person
       |compradlas;verb;;accusative,combined-form,imperative,informal,object-plural,object-third-person,plural,second-person
       |compradlas;verb;;accusative,imperative,object-feminine,object-plural,object-third-person,plural,second-person
       |compradle;verb;;combined-form,dative,imperative,informal,object-singular,object-third-person,plural,second-person
       |compradle;verb;;dative,imperative,object-singular,object-third-person,plural,second-person
       |compradles;verb;;combined-form,dative,imperative,informal,object-plural,object-third-person,plural,second-person
       |compradles;verb;;dative,imperative,object-plural,object-third-person,plural,second-person
       |compradlo;verb;;accusative,combined-form,imperative,informal,object-singular,object-third-person,plural,second-person
       |compradlo;verb;;accusative,imperative,object-masculine,object-singular,object-third-person,plural,second-person
       |compradlos;verb;;accusative,combined-form,imperative,informal,object-plural,object-third-person,plural,second-person
       |compradlos;verb;;accusative,imperative,object-masculine,object-plural,object-third-person,plural,second-person
       |compradme;verb;;accusative,combined-form,imperative,informal,object-first-person,object-singular,plural,second-person
       |compradme;verb;;combined-form,dative,imperative,informal,object-first-person,object-singular,plural,second-person
       |compradme;verb;;imperative,object-first-person,object-singular,plural,second-person
       |compradnos;verb;;accusative,combined-form,imperative,informal,object-first-person,object-plural,plural,second-person
       |compradnos;verb;;combined-form,dative,imperative,informal,object-first-person,object-plural,plural,second-person
       |compradnos;verb;;imperative,object-first-person,object-plural,plural,second-person
       |comprado;verb;;masculine,participle,past,singular
       |comprado;verb;;participle,past
       |comprados;verb;;masculine,participle,past,plural
       |comprala;verb;;accusative,combined-form,imperative,informal,object-singular,object-third-person,second-person,singular,with-vos
       |comprala;verb;;accusative,imperative,object-feminine,object-singular,object-third-person,second-person,singular,with-voseo
       |compralas;verb;;accusative,combined-form,imperative,informal,object-plural,object-third-person,second-person,singular,with-vos
       |comprale;verb;;combined-form,dative,imperative,informal,object-singular,object-third-person,second-person,singular,with-vos
       |comprale;verb;;dative,imperative,object-singular,object-third-person,second-person,singular,with-voseo
       |comprales;verb;;combined-form,dative,imperative,informal,object-plural,object-third-person,second-person,singular,with-vos
       |compralo;verb;;accusative,combined-form,imperative,informal,object-singular,object-third-person,second-person,singular,with-vos
       |compralo;verb;;accusative,imperative,object-masculine,object-singular,object-third-person,second-person,singular,with-voseo
       |compralos;verb;;accusative,combined-form,imperative,informal,object-plural,object-third-person,second-person,singular,with-vos
       |comprame;verb;;accusative,combined-form,imperative,informal,object-first-person,object-singular,second-person,singular,with-vos
       |comprame;verb;;combined-form,dative,imperative,informal,object-first-person,object-singular,second-person,singular,with-vos
       |compramos;verb;;first-person,indicative,plural,present
       |compramos;verb;;first-person,indicative,plural,present,preterite
       |compramos;verb;;first-person,indicative,plural,preterite
       |compran;verb;;indicative,plural,present,third-person
       |comprando;verb;;gerund
       |compranos;verb;;accusative,combined-form,imperative,informal,object-first-person,object-plural,second-person,singular,with-vos
       |compranos;verb;;combined-form,dative,imperative,informal,object-first-person,object-plural,second-person,singular,with-vos
       |compraos;verb;;accusative,combined-form,imperative,informal,object-plural,object-second-person,plural,second-person
       |compraos;verb;;combined-form,dative,imperative,informal,object-plural,object-second-person,plural,second-person
       |compraos;verb;;imperative,object-plural,object-second-person,plural,second-person
       |compraos;verb;;imperative,plural,second-person
       |comprar;verb;;infinitive
       |comprara;verb;;first-person,imperfect,singular,subjunctive
       |comprara;verb;;first-person,imperfect,singular,subjunctive,third-person
       |comprara;verb;;imperfect,singular,subjunctive,third-person
       |comprarais;verb;;imperfect,plural,second-person,subjunctive
       |compraran;verb;;imperfect,plural,subjunctive,third-person
       |compraras;verb;;imperfect,second-person,singular,subjunctive
       |comprare;verb;;first-person,future,singular,subjunctive
       |comprare;verb;;first-person,future,singular,subjunctive,third-person
       |comprare;verb;;future,singular,subjunctive,third-person
       |comprareis;verb;;future,plural,second-person,subjunctive
       |compraremos;verb;;first-person,future,indicative,plural
       |compraren;verb;;future,plural,subjunctive,third-person
       |comprares;verb;;future,second-person,singular,subjunctive
       |comprarla;verb;;accusative,combined-form,infinitive,object-singular,object-third-person
       |comprarla;verb;;accusative,infinitive,object-feminine,object-singular,object-third-person
       |comprarlas;verb;;accusative,combined-form,infinitive,object-plural,object-third-person
       |comprarlas;verb;;accusative,infinitive,object-feminine,object-plural,object-third-person
       |comprarle;verb;;combined-form,dative,infinitive,object-singular,object-third-person
       |comprarle;verb;;dative,infinitive,object-singular,object-third-person
       |comprarles;verb;;combined-form,dative,infinitive,object-plural,object-third-person
       |comprarles;verb;;dative,infinitive,object-plural,object-third-person
       |comprarlo;verb;;accusative,combined-form,infinitive,object-singular,object-third-person
       |comprarlo;verb;;accusative,infinitive,object-masculine,object-singular,object-third-person
       |comprarlos;verb;;accusative,combined-form,infinitive,object-plural,object-third-person
       |comprarlos;verb;;accusative,infinitive,object-masculine,object-plural,object-third-person
       |comprarme;verb;;accusative,combined-form,infinitive,object-first-person,object-singular
       |comprarme;verb;;combined-form,dative,infinitive,object-first-person,object-singular
       |comprarme;verb;;first-person,infinitive,singular
       |comprarme;verb;;infinitive,object-first-person,object-singular
       |comprarnos;verb;;accusative,combined-form,infinitive,object-first-person,object-plural
       |comprarnos;verb;;combined-form,dative,infinitive,object-first-person,object-plural
       |comprarnos;verb;;first-person,infinitive,plural
       |comprarnos;verb;;infinitive,object-first-person,object-plural
       |compraron;verb;;indicative,plural,preterite,third-person
       |compraros;verb;;accusative,combined-form,infinitive,object-plural,object-second-person
       |compraros;verb;;combined-form,dative,infinitive,object-plural,object-second-person
       |compraros;verb;;infinitive,object-plural,object-second-person
       |compraros;verb;;infinitive,plural,second-person
       |comprarse;verb;;accusative,combined-form,infinitive,object-plural,object-third-person
       |comprarse;verb;;accusative,combined-form,infinitive,object-singular,object-third-person
       |comprarse;verb;;combined-form,dative,infinitive,object-plural,object-third-person
       |comprarse;verb;;combined-form,dative,infinitive,object-singular,object-third-person
       |comprarse;verb;;infinitive
       |comprarse;verb;;infinitive,plural,third-person
       |comprarse;verb;;infinitive,reflexive
       |comprarse;verb;;infinitive,singular,third-person
       |comprarte;verb;;accusative,combined-form,infinitive,object-second-person,object-singular
       |comprarte;verb;;combined-form,dative,infinitive,object-second-person,object-singular
       |comprarte;verb;;infinitive,object-second-person,object-singular
       |comprarte;verb;;infinitive,second-person,singular
       |comprará;verb;;future,indicative,singular,third-person
       |comprarán;verb;;future,indicative,plural,third-person
       |comprarás;verb;;future,indicative,second-person,singular
       |compraré;verb;;first-person,future,indicative,singular
       |compraréis;verb;;future,indicative,plural,second-person
       |compraría;verb;;conditional,first-person,indicative,singular
       |compraría;verb;;conditional,first-person,singular,third-person
       |compraría;verb;;conditional,indicative,singular,third-person
       |compraríais;verb;;conditional,indicative,plural,second-person
       |compraríais;verb;;conditional,plural,second-person
       |compraríamos;verb;;conditional,first-person,indicative,plural
       |compraríamos;verb;;conditional,first-person,plural
       |comprarían;verb;;conditional,indicative,plural,third-person
       |comprarían;verb;;conditional,plural,third-person
       |comprarías;verb;;conditional,indicative,second-person,singular
       |comprarías;verb;;conditional,second-person,singular
       |compras;verb;;indicative,informal,present,second-person,singular
       |compras;verb;;indicative,present,second-person,singular
       |comprase;verb;;first-person,imperfect,imperfect-se,singular,subjunctive
       |comprase;verb;;first-person,imperfect,singular,subjunctive,third-person
       |comprase;verb;;imperfect,imperfect-se,singular,subjunctive,third-person
       |compraseis;verb;;imperfect,imperfect-se,plural,second-person,subjunctive
       |compraseis;verb;;imperfect,plural,second-person,subjunctive
       |comprasen;verb;;imperfect,imperfect-se,plural,subjunctive,third-person
       |comprasen;verb;;imperfect,plural,subjunctive,third-person
       |comprases;verb;;imperfect,imperfect-se,second-person,singular,subjunctive
       |comprases;verb;;imperfect,second-person,singular,subjunctive
       |compraste;verb;;indicative,preterite,second-person,singular
       |comprasteis;verb;;indicative,plural,preterite,second-person
       |comprate;verb;;accusative,combined-form,imperative,informal,object-second-person,object-singular,second-person,singular,with-vos
       |comprate;verb;;combined-form,dative,imperative,informal,object-second-person,object-singular,second-person,singular,with-vos
       |comprate;verb;;imperative,informal,second-person,singular,vos-form
       |comprate;verb;;imperative,object-second-person,object-singular,second-person,singular,with-voseo
       |compre;verb;;first-person,present,singular,subjunctive
       |compre;verb;;first-person,present,singular,subjunctive,third-person
       |compre;verb;;formal,imperative,negative,second-person-semantically,singular,third-person
       |compre;verb;;formal,imperative,second-person-semantically,singular,third-person
       |compre;verb;;imperative,negative,singular,third-person
       |compre;verb;;imperative,singular,third-person
       |compre;verb;;present,singular,subjunctive,third-person
       |compremos;verb;;first-person,imperative,negative,plural
       |compremos;verb;;first-person,imperative,plural
       |compremos;verb;;first-person,plural,present,subjunctive
       |compren;verb;;formal,imperative,negative,plural,second-person-semantically,third-person
       |compren;verb;;formal,imperative,plural,second-person-semantically,third-person
       |compren;verb;;imperative,negative,plural,third-person
       |compren;verb;;imperative,plural,third-person
       |compren;verb;;plural,present,subjunctive,third-person
       |compres;verb;;imperative,negative,second-person,singular
       |compres;verb;;informal,present,second-person,singular,subjunctive
       |compres;verb;;present,second-person,singular,subjunctive
       |compro;verb;;first-person,indicative,present,singular
       |compro;verb;;first-person,present,singular
       |comprá;verb;;imperative,informal,second-person,singular,vos-form
       |comprá;verb;;imperative,second-person,singular,with-voseo
       |comprábamos;verb;;first-person,imperfect,indicative,plural
       |compráis;verb;;indicative,plural,present,second-person
       |comprándola;verb;;accusative,combined-form,gerund,object-singular,object-third-person
       |comprándola;verb;;accusative,gerund,object-feminine,object-singular,object-third-person
       |comprándolas;verb;;accusative,combined-form,gerund,object-plural,object-third-person
       |comprándolas;verb;;accusative,gerund,object-feminine,object-plural,object-third-person
       |comprándole;verb;;combined-form,dative,gerund,object-singular,object-third-person
       |comprándole;verb;;dative,gerund,object-singular,object-third-person
       |comprándoles;verb;;combined-form,dative,gerund,object-plural,object-third-person
       |comprándoles;verb;;dative,gerund,object-plural,object-third-person
       |comprándolo;verb;;accusative,combined-form,gerund,object-singular,object-third-person
       |comprándolo;verb;;accusative,gerund,object-masculine,object-singular,object-third-person
       |comprándolos;verb;;accusative,combined-form,gerund,object-plural,object-third-person
       |comprándolos;verb;;accusative,gerund,object-masculine,object-plural,object-third-person
       |comprándome;verb;;accusative,combined-form,gerund,object-first-person,object-singular
       |comprándome;verb;;combined-form,dative,gerund,object-first-person,object-singular
       |comprándome;verb;;first-person,gerund,singular
       |comprándome;verb;;gerund,object-first-person,object-singular
       |comprándonos;verb;;accusative,combined-form,gerund,object-first-person,object-plural
       |comprándonos;verb;;combined-form,dative,gerund,object-first-person,object-plural
       |comprándonos;verb;;first-person,gerund,plural
       |comprándonos;verb;;gerund,object-first-person,object-plural
       |comprándoos;verb;;accusative,combined-form,gerund,object-plural,object-second-person
       |comprándoos;verb;;combined-form,dative,gerund,object-plural,object-second-person
       |comprándoos;verb;;gerund,object-plural,object-second-person
       |comprándoos;verb;;gerund,plural,second-person
       |comprándose;verb;;accusative,combined-form,gerund,object-plural,object-third-person
       |comprándose;verb;;accusative,combined-form,gerund,object-singular,object-third-person
       |comprándose;verb;;combined-form,dative,gerund,object-plural,object-third-person
       |comprándose;verb;;combined-form,dative,gerund,object-singular,object-third-person
       |comprándose;verb;;gerund
       |comprándose;verb;;gerund,plural,third-person
       |comprándose;verb;;gerund,reflexive
       |comprándose;verb;;gerund,singular,third-person
       |comprándote;verb;;accusative,combined-form,gerund,object-second-person,object-singular
       |comprándote;verb;;combined-form,dative,gerund,object-second-person,object-singular
       |comprándote;verb;;gerund,object-second-person,object-singular
       |comprándote;verb;;gerund,second-person,singular
       |compráramos;verb;;first-person,imperfect,plural,subjunctive
       |compráremos;verb;;first-person,future,plural,subjunctive
       |comprás;verb;;indicative,informal,present,second-person,singular,vos-form
       |comprás;verb;;indicative,present,second-person,singular,with-voseo
       |comprásemos;verb;;first-person,imperfect,imperfect-se,plural,subjunctive
       |comprásemos;verb;;first-person,imperfect,plural,subjunctive
       |compré;verb;;first-person,indicative,preterite,singular
       |compré;verb;;first-person,preterite,singular
       |compréis;verb;;imperative,negative,plural,second-person
       |compréis;verb;;plural,present,second-person,subjunctive
       |comprémonos;verb;;accusative,combined-form,first-person,imperative,object-first-person,object-plural,plural
       |comprémonos;verb;;combined-form,dative,first-person,imperative,object-first-person,object-plural,plural
       |comprémonos;verb;;first-person,imperative,object-first-person,object-plural,plural
       |comprémonos;verb;;first-person,imperative,plural
       |comprémoos;verb;;accusative,combined-form,first-person,imperative,object-plural,object-second-person,plural
       |comprémoos;verb;;combined-form,dative,first-person,imperative,object-plural,object-second-person,plural
       |comprémosla;verb;;accusative,combined-form,first-person,imperative,object-singular,object-third-person,plural
       |comprémosla;verb;;accusative,first-person,imperative,object-feminine,object-singular,object-third-person,plural
       |comprémoslas;verb;;accusative,combined-form,first-person,imperative,object-plural,object-third-person,plural
       |comprémoslas;verb;;accusative,first-person,imperative,object-feminine,object-plural,object-third-person,plural
       |comprémosle;verb;;combined-form,dative,first-person,imperative,object-singular,object-third-person,plural
       |comprémosle;verb;;dative,first-person,imperative,object-singular,object-third-person,plural
       |comprémosles;verb;;combined-form,dative,first-person,imperative,object-plural,object-third-person,plural
       |comprémosles;verb;;dative,first-person,imperative,object-plural,object-third-person,plural
       |comprémoslo;verb;;accusative,combined-form,first-person,imperative,object-singular,object-third-person,plural
       |comprémoslo;verb;;accusative,first-person,imperative,object-masculine,object-singular,object-third-person,plural
       |comprémoslos;verb;;accusative,combined-form,first-person,imperative,object-plural,object-third-person,plural
       |comprémoslos;verb;;accusative,first-person,imperative,object-masculine,object-plural,object-third-person,plural
       |comprémoste;verb;;accusative,combined-form,first-person,imperative,object-second-person,object-singular,plural
       |comprémoste;verb;;combined-form,dative,first-person,imperative,object-second-person,object-singular,plural
       |comprés;verb;;informal,present,second-person,singular,subjunctive,vos-form
       |comprés;verb;;present,second-person,singular,subjunctive,with-voseo
       |compró;verb;;indicative,preterite,singular,third-person
       |cómprala;verb;;accusative,combined-form,imperative,informal,object-singular,object-third-person,second-person,singular,with-tú
       |cómprala;verb;;accusative,imperative,object-feminine,object-singular,object-third-person,second-person,singular
       |cómpralas;verb;;accusative,combined-form,imperative,informal,object-plural,object-third-person,second-person,singular,with-tú
       |cómpralas;verb;;accusative,imperative,object-feminine,object-plural,object-third-person,second-person,singular
       |cómprale;verb;;combined-form,dative,imperative,informal,object-singular,object-third-person,second-person,singular,with-tú
       |cómprale;verb;;dative,imperative,object-singular,object-third-person,second-person,singular
       |cómprales;verb;;combined-form,dative,imperative,informal,object-plural,object-third-person,second-person,singular,with-tú
       |cómprales;verb;;dative,imperative,object-plural,object-third-person,second-person,singular
       |cómpralo;verb;;accusative,combined-form,imperative,informal,object-singular,object-third-person,second-person,singular,with-tú
       |cómpralo;verb;;accusative,imperative,object-masculine,object-singular,object-third-person,second-person,singular
       |cómpralos;verb;;accusative,combined-form,imperative,informal,object-plural,object-third-person,second-person,singular,with-tú
       |cómpralos;verb;;accusative,imperative,object-masculine,object-plural,object-third-person,second-person,singular
       |cómprame;verb;;accusative,combined-form,imperative,informal,object-first-person,object-singular,second-person,singular,with-tú
       |cómprame;verb;;combined-form,dative,imperative,informal,object-first-person,object-singular,second-person,singular,with-tú
       |cómprame;verb;;imperative,object-first-person,object-singular,second-person,singular
       |cómpranos;verb;;accusative,combined-form,imperative,informal,object-first-person,object-plural,second-person,singular,with-tú
       |cómpranos;verb;;combined-form,dative,imperative,informal,object-first-person,object-plural,second-person,singular,with-tú
       |cómpranos;verb;;imperative,object-first-person,object-plural,second-person,singular
       |cómprate;verb;;accusative,combined-form,imperative,informal,object-second-person,object-singular,second-person,singular,with-tú
       |cómprate;verb;;combined-form,dative,imperative,informal,object-second-person,object-singular,second-person,singular,with-tú
       |cómprate;verb;;imperative,informal,second-person,singular
       |cómprate;verb;;imperative,object-second-person,object-singular,second-person,singular
       |cómprela;verb;;accusative,combined-form,formal,imperative,object-singular,object-third-person,second-person,singular
       |cómprela;verb;;accusative,imperative,object-feminine,object-singular,object-third-person,singular,third-person
       |cómprelas;verb;;accusative,combined-form,formal,imperative,object-plural,object-third-person,second-person,singular
       |cómprelas;verb;;accusative,imperative,object-feminine,object-plural,object-third-person,singular,third-person
       |cómprele;verb;;combined-form,dative,formal,imperative,object-singular,object-third-person,second-person,singular
       |cómprele;verb;;dative,imperative,object-singular,object-third-person,singular,third-person
       |cómpreles;verb;;combined-form,dative,formal,imperative,object-plural,object-third-person,second-person,singular
       |cómpreles;verb;;dative,imperative,object-plural,object-third-person,singular,third-person
       |cómprelo;verb;;accusative,combined-form,formal,imperative,object-singular,object-third-person,second-person,singular
       |cómprelo;verb;;accusative,imperative,object-masculine,object-singular,object-third-person,singular,third-person
       |cómprelos;verb;;accusative,combined-form,formal,imperative,object-plural,object-third-person,second-person,singular
       |cómprelos;verb;;accusative,imperative,object-masculine,object-plural,object-third-person,singular,third-person
       |cómpreme;verb;;accusative,combined-form,formal,imperative,object-first-person,object-singular,second-person,singular
       |cómpreme;verb;;combined-form,dative,formal,imperative,object-first-person,object-singular,second-person,singular
       |cómpreme;verb;;imperative,object-first-person,object-singular,singular,third-person
       |cómprenla;verb;;accusative,combined-form,formal,imperative,object-singular,object-third-person,plural,second-person
       |cómprenla;verb;;accusative,imperative,object-feminine,object-singular,object-third-person,plural,third-person
       |cómprenlas;verb;;accusative,combined-form,formal,imperative,object-plural,object-third-person,plural,second-person
       |cómprenlas;verb;;accusative,imperative,object-feminine,object-plural,object-third-person,plural,third-person
       |cómprenle;verb;;combined-form,dative,formal,imperative,object-singular,object-third-person,plural,second-person
       |cómprenle;verb;;dative,imperative,object-singular,object-third-person,plural,third-person
       |cómprenles;verb;;combined-form,dative,formal,imperative,object-plural,object-third-person,plural,second-person
       |cómprenles;verb;;dative,imperative,object-plural,object-third-person,plural,third-person
       |cómprenlo;verb;;accusative,combined-form,formal,imperative,object-singular,object-third-person,plural,second-person
       |cómprenlo;verb;;accusative,imperative,object-masculine,object-singular,object-third-person,plural,third-person
       |cómprenlos;verb;;accusative,combined-form,formal,imperative,object-plural,object-third-person,plural,second-person
       |cómprenlos;verb;;accusative,imperative,object-masculine,object-plural,object-third-person,plural,third-person
       |cómprenme;verb;;accusative,combined-form,formal,imperative,object-first-person,object-singular,plural,second-person
       |cómprenme;verb;;combined-form,dative,formal,imperative,object-first-person,object-singular,plural,second-person
       |cómprenme;verb;;imperative,object-first-person,object-singular,plural,third-person
       |cómprennos;verb;;accusative,combined-form,formal,imperative,object-first-person,object-plural,plural,second-person
       |cómprennos;verb;;combined-form,dative,formal,imperative,object-first-person,object-plural,plural,second-person
       |cómprennos;verb;;imperative,object-first-person,object-plural,plural,third-person
       |cómprenos;verb;;accusative,combined-form,formal,imperative,object-first-person,object-plural,second-person,singular
       |cómprenos;verb;;combined-form,dative,formal,imperative,object-first-person,object-plural,second-person,singular
       |cómprenos;verb;;imperative,object-first-person,object-plural,singular,third-person
       |cómprense;verb;;accusative,combined-form,formal,imperative,object-plural,object-third-person,plural,second-person
       |cómprense;verb;;combined-form,dative,formal,imperative,object-plural,object-third-person,plural,second-person
       |cómprense;verb;;formal,imperative,plural,second-person-semantically,third-person
       |cómprense;verb;;imperative,plural,reflexive,third-person
       |cómprense;verb;;imperative,plural,third-person
       |cómprese;verb;;accusative,combined-form,formal,imperative,object-singular,object-third-person,second-person,singular
       |cómprese;verb;;combined-form,dative,formal,imperative,object-singular,object-third-person,second-person,singular
       |cómprese;verb;;formal,imperative,second-person-semantically,singular,third-person
       |cómprese;verb;;imperative,reflexive,singular,third-person
       |cómprese;verb;;imperative,singular,third-person
       |me compraba;verb;;first-person,imperfect,indicative,reflexive,singular
       |me comprara;verb;;first-person,imperfect,reflexive,singular,subjunctive
       |me comprare;verb;;first-person,future,reflexive,singular,subjunctive
       |me compraré;verb;;first-person,future,indicative,reflexive,singular
       |me compraría;verb;;conditional,first-person,indicative,reflexive,singular
       |me comprase;verb;;first-person,imperfect,imperfect-se,reflexive,singular,subjunctive
       |me compre;verb;;first-person,present,reflexive,singular,subjunctive
       |me compro;verb;;first-person,indicative,present,reflexive,singular
       |me compré;verb;;first-person,indicative,preterite,reflexive,singular
       |nos compramos;verb;;first-person,indicative,plural,present,reflexive
       |nos compramos;verb;;first-person,indicative,plural,preterite,reflexive
       |nos compraremos;verb;;first-person,future,indicative,plural,reflexive
       |nos compraríamos;verb;;conditional,first-person,indicative,plural,reflexive
       |nos compremos;verb;;first-person,imperative,negative,plural,reflexive
       |nos compremos;verb;;first-person,plural,present,reflexive,subjunctive
       |nos comprábamos;verb;;first-person,imperfect,indicative,plural,reflexive
       |nos compráramos;verb;;first-person,imperfect,plural,reflexive,subjunctive
       |nos compráremos;verb;;first-person,future,plural,reflexive,subjunctive
       |nos comprásemos;verb;;first-person,imperfect,imperfect-se,plural,reflexive,subjunctive
       |os comprabais;verb;;imperfect,indicative,plural,reflexive,second-person
       |os comprarais;verb;;imperfect,plural,reflexive,second-person,subjunctive
       |os comprareis;verb;;future,plural,reflexive,second-person,subjunctive
       |os compraréis;verb;;future,indicative,plural,reflexive,second-person
       |os compraríais;verb;;conditional,indicative,plural,reflexive,second-person
       |os compraseis;verb;;imperfect,imperfect-se,plural,reflexive,second-person,subjunctive
       |os comprasteis;verb;;indicative,plural,preterite,reflexive,second-person
       |os compráis;verb;;indicative,plural,present,reflexive,second-person
       |os compréis;verb;;imperative,negative,plural,reflexive,second-person
       |os compréis;verb;;plural,present,reflexive,second-person,subjunctive
       |se compra;verb;;indicative,present,reflexive,singular,third-person
       |se compraba;verb;;imperfect,indicative,reflexive,singular,third-person
       |se compraban;verb;;imperfect,indicative,plural,reflexive,third-person
       |se compran;verb;;indicative,plural,present,reflexive,third-person
       |se comprara;verb;;imperfect,reflexive,singular,subjunctive,third-person
       |se compraran;verb;;imperfect,plural,reflexive,subjunctive,third-person
       |se comprare;verb;;future,reflexive,singular,subjunctive,third-person
       |se compraren;verb;;future,plural,reflexive,subjunctive,third-person
       |se compraron;verb;;indicative,plural,preterite,reflexive,third-person
       |se comprará;verb;;future,indicative,reflexive,singular,third-person
       |se comprarán;verb;;future,indicative,plural,reflexive,third-person
       |se compraría;verb;;conditional,indicative,reflexive,singular,third-person
       |se comprarían;verb;;conditional,indicative,plural,reflexive,third-person
       |se comprase;verb;;imperfect,imperfect-se,reflexive,singular,subjunctive,third-person
       |se comprasen;verb;;imperfect,imperfect-se,plural,reflexive,subjunctive,third-person
       |se compre;verb;;formal,imperative,negative,reflexive,second-person-semantically,singular,third-person
       |se compre;verb;;imperative,negative,reflexive,singular,third-person
       |se compre;verb;;present,reflexive,singular,subjunctive,third-person
       |se compren;verb;;formal,imperative,negative,plural,reflexive,second-person-semantically,third-person
       |se compren;verb;;imperative,negative,plural,reflexive,third-person
       |se compren;verb;;plural,present,reflexive,subjunctive,third-person
       |se compró;verb;;indicative,preterite,reflexive,singular,third-person
       |te comprabas;verb;;imperfect,indicative,reflexive,second-person,singular
       |te compraras;verb;;imperfect,reflexive,second-person,singular,subjunctive
       |te comprares;verb;;future,reflexive,second-person,singular,subjunctive
       |te comprarás;verb;;future,indicative,reflexive,second-person,singular
       |te comprarías;verb;;conditional,indicative,reflexive,second-person,singular
       |te compras;verb;;indicative,informal,present,reflexive,second-person,singular
       |te comprases;verb;;imperfect,imperfect-se,reflexive,second-person,singular,subjunctive
       |te compraste;verb;;indicative,preterite,reflexive,second-person,singular
       |te compres;verb;;imperative,negative,reflexive,second-person,singular
       |te compres;verb;;informal,present,reflexive,second-person,singular,subjunctive
       |te comprás;verb;;indicative,informal,present,reflexive,second-person,singular,vos-form
       |te comprés;verb;;informal,present,reflexive,second-person,singular,subjunctive,vos-form""".stripMargin
  }

  val esCasa: String = {
    """casas;noun;;plural""".stripMargin
  }

  val esNino: String = {
    """niñas;noun;feminine;feminine,plural
       |niños;noun;masculine;plural""".stripMargin
  }

  val esBueno: String = {
    """before a noun buen;adjective;;masculine,singular
       |bonísimo;adjective;;dated,formal,superlative
       |buena;adjective;;feminine
       |buena;adjective;;feminine,singular
       |buenas;adjective;;feminine,plural
       |buenos;adjective;;masculine,plural
       |buenísimo;adjective;;superlative
       |mejor;adjective;;comparative
       |más bueno;adjective;;comparative
       |óptimo;adjective;;superlative""".stripMargin
  }

  val esLibre: String = {
    """libres;adjective;;feminine,masculine,plural
       |libres;adjective;;plural
       |libérrimo;adjective;;superlative""".stripMargin
  }
}
