package com.rad.shifttracker

/**
 * Listići 01–08/2026 skraćeni na redove koje parser koristi: zaglavlje meseca,
 * fond i stavke obračuna. Bez imena, JMBG-a, adrese, računa i obustava.
 */
internal val PAYSLIPS_2026: Map<Int, List<String>> = mapOf(
    1 to """
        OBRAČUN ZARADE I NAKNADE ZARADE ZA MESEC 01/2026 rb.2/konačna
        Fond za mesec : 176 Koeficijent : 2.82 Vrednost sata : 226.00 Osnovna zarada : 112,168.32
        95 Izostanci 7.0 0
        KIR K I R U 10 6691.86
        MR Minuli rad 3.5 2342.15
        TO2 Topli obrok 5320.97
        IA Isplacena akontacija -100 -38139.01
        01 Rad u reziji 105.0 66918.6
        10 Godisnji odmor 40.0 36514.83
        12 Drzavni praznik 24.0 21908.9
        20 Nocni rad 24.0 41 6271.23
        30 Prekovremeni rad 5.0 3186.6
        24 Uvecanje za prekovremeni rad 5.0 41 1306.51
    """.trimIndent().lines(),
    2 to """
        OBRAČUN ZARADE I NAKNADE ZARADE ZA MESEC 02/2026 rb.4/konačna
        Fond za mesec : 160 Koeficijent : 2.82 Vrednost sata : 226.00 Osnovna zarada : 101,971.20
        95 Izostanci 13.0 0
        05 Rad u reziji na DP 8.0 5098.56
        96 Izostanci 1 -100 -330
        MR Minuli rad 3.5 3100.56
        REG Regres 28530.67
        TO2 Topli obrok 7589.16
        IA Isplacena akontacija -100 -61062.77
        01 Rad u reziji 131.0 83488.92
        12 Drzavni praznik 8.0 7384.57
        20 Nocni rad 39.0 41 10190.75
        22 Rad nedeljom 8.0 25 1274.64
        23 Rad na praznik 8.0 123 6271.23
        30 Prekovremeni rad 8.0 5098.56
        24 Uvecanje za prekovremeni rad 8.0 41 2090.41
    """.trimIndent().lines(),
    3 to """
        OBRAČUN ZARADE I NAKNADE ZARADE ZA MESEC 03/2026 rb.3/konačna
        Fond za mesec : 176 Koeficijent : 2.82 Vrednost sata : 226.00 Osnovna zarada : 112,168.32
        GN GODIŠNJA NAGRADA 28531
        95 Izostanci 2.0 0
        MR Minuli rad 3.5 2989.03
        TO2 Topli obrok 6048.5
        IA Isplacena akontacija -100 -65726.1
        01 Rad u reziji 134.0 85400.88
        20 Nocni rad 78.0 41 20381.49
        30 Prekovremeni rad 3.0 1911.96
        24 Uvecanje za prekovremeni rad 3.0 41 783.9
        40 Bolovanje za tekuci mesec 40.0 65 23888.93
    """.trimIndent().lines(),
    4 to """
        OBRAČUN ZARADE I NAKNADE ZARADE ZA MESEC 04/2026 rb.3/konačna
        Fond za mesec : 176 Koeficijent : 2.82 Vrednost sata : 226.00 Osnovna zarada : 112,168.32
        95 Izostanci 35.0 0
        96 Izostanci 1 -100 -495
        MR Minuli rad 3.5 2788.28
        TO2 Topli obrok 5706.13
        IA Isplacena akontacija -100 -49820.12
        01 Rad u reziji 125.0 79665
        12 Drzavni praznik 16.0 15250.93
        20 Nocni rad 26.0 41 6793.83
    """.trimIndent().lines(),
    5 to """
        OBRAČUN ZARADE I NAKNADE ZARADE ZA MESEC 05/2026 rb.3/konačna
        Fond za mesec : 168 Koeficijent : 2.82 Vrednost sata : 226.00 Osnovna zarada : 107,069.76
        95 Izostanci 7.0 0
        96 Izostanci 1 -100 -495
        05 Rad u reziji na DP 13.0 8285.16
        MR Minuli rad 3.5 3234.4
        REG Regres 28530.67
        TO2 Topli obrok 7817.4
        IA Isplacena akontacija -100 -48587.9
        01 Rad u reziji 132.0 84126.24
        11 Placeno odsustvo 16.0 15209.29
        20 Nocni rad 56.0 41 14632.87
        23 Rad na praznik 13.0 123 10190.75
        30 Prekovremeni rad 6.0 3823.92
        24 Uvecanje za prekovremeni rad 6.0 41 1567.81
    """.trimIndent().lines(),
    6 to """
        OBRAČUN ZARADE I NAKNADE ZARADE ZA MESEC 06/2026 rb.4/konačna
        Fond za mesec : 176 Koeficijent : 2.82 Vrednost sata : 226.00 Osnovna zarada : 112,168.32
        96 Izostanci 1 -100 -330
        95 Izostanci 37.0 0
        MR Minuli rad 3.5 3100.56
        TO2 Topli obrok 5478
        IA Isplacena akontacija -100 -52895.87
        01 Rad u reziji 139.0 88587.48
        20 Nocni rad 43.0 41 11235.95
        30 Prekovremeni rad 6.0 3823.92
        24 Uvecanje za prekovremeni rad 6.0 41 1567.81
    """.trimIndent().lines(),
    7 to """
        OBRAČUN ZARADE I NAKNADE ZARADE ZA MESEC 07/2026 rb.2/konačna
        Fond za mesec : 184 Koeficijent : 2.82 Vrednost sata : 226.00 Osnovna zarada : 117,266.88
        96 Izostanci 1 -100 -330
        95 Izostanci 3.0 0
        81 Stimulacije 6823
        MR Minuli rad 3.5 1896.03
        TO2 Topli obrok 4129.81
        IA Isplacena akontacija -100 -27827.9
        01 Rad u reziji 85.0 54172.2
        10 Godisnji odmor 96.0 90777.85
        20 Nocni rad 60.0 41 15678.07
        22 Rad nedeljom 4.0 25 637.32
        30 Prekovremeni rad 17.0 10834.44
        24 Uvecanje za prekovremeni rad 17.0 41 4442.12
    """.trimIndent().lines(),
    8 to """
        OBRAČUN ZARADE I NAKNADE ZARADE ZA MESEC 08/2026 rb.3/konačna
        Fond za mesec : 168 Koeficijent : 2.82 Vrednost sata : 226.00 Osnovna zarada : 107,069.76
        95 Izostanci 22.0 0
        96 Izostanci 1 -100 -478
        81 Stimulacije 10699
        KIR K I R U -5 -3632.72
        MR Minuli rad 3.5 2542.91
        REG Regres 28530.67
        TO2 Topli obrok 6191.16
        IA Isplacena akontacija -100 -84082.73
        01 Rad u reziji 114.0 72654.47
        10 Godisnji odmor 16.0 15267.88
        11 Placeno odsustvo 16.0 15267.88
        20 Nocni rad 43.0 41 11235.95
        22 Rad nedeljom 8.0 25 1274.64
        30 Prekovremeni rad 12.0 7647.84
        24 Uvecanje za prekovremeni rad 12.0 41 3135.61
    """.trimIndent().lines()
)
