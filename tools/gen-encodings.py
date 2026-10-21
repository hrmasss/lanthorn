#!/usr/bin/env python3
"""Subtitle files in every encoding US-44.1 names, several lines of real text each (detection
needs context). Writes <out>/<name>.srt and <out>/expected.tsv (name, first line in UTF-8, NFC).
Usage: gen-encodings.py <out dir>"""
import os, sys, unicodedata

TEXTS = {
    "cs": ["Příliš žluťoučký kůň úpěl ďábelské ódy.", "Dobrý den, jak se dnes máte?", "Večer půjdeme do kina na nový film.", "Těším se na víkend u babičky.", "Prší už třetí den, vezmi si deštník.", "Kde je nejbližší nádraží?", "Děkuji za pomoc, byl jste velmi laskav.", "Zítra ráno vyrážíme na hory."],
    "ru": ["Съешь же ещё этих мягких французских булок.", "Добрый вечер, как прошёл ваш день?", "Мы встретимся завтра у старого моста.", "Он никогда не видел такого снега.", "Пожалуйста, закройте дверь за собой.", "Поезд опаздывает на двадцать минут.", "Я думаю, что это хорошая идея.", "Спасибо, увидимся на следующей неделе."],
    "fr": ["Où est passé le garçon qui jouait près de la fenêtre ?", "Ça fait déjà trois jours qu'il pleut à Montréal.", "Elle a mangé une crêpe brûlée au café.", "Nous irons à la pêche dès l'aube.", "Le château était entouré d'une forêt épaisse.", "Je n'ai jamais vu un été aussi chaud.", "Votre colis arrivera après-demain.", "Merci beaucoup, à bientôt !"],
    "el": ["Καλημέρα, τι κάνετε σήμερα;", "Η θάλασσα ήταν ήρεμη όλο το απόγευμα.", "Θα συναντηθούμε στο καφενείο της πλατείας.", "Ο σκύλος έτρεξε προς τον κήπο.", "Δεν έχω ξαναδεί τέτοιο ηλιοβασίλεμα.", "Το τρένο φεύγει στις οκτώ ακριβώς.", "Ευχαριστώ πολύ για τη βοήθειά σας.", "Τα λέμε αύριο το πρωί."],
    "tr": ["Pijamalı hasta yağız şoföre çabucak güvendi.", "Bugün hava çok güzel, dışarı çıkalım mı?", "Öğretmen öğrencilere ödev verdi.", "İstanbul'da akşam trafiği çok yoğun.", "Çayını şekersiz mi içiyorsun?", "Yarın sabah erkenden yola çıkacağız.", "Bu kitabı geçen hafta bitirdim.", "Görüşmek üzere, kendine iyi bak."],
    "he": ["שלום, מה שלומך היום?", "הים היה שקט כל אחר הצהריים.", "ניפגש מחר ליד התחנה הישנה.", "הכלב רץ לכיוון הגינה.", "אני לא מאמין שזה קרה שוב.", "הרכבת יוצאת בשמונה בדיוק.", "תודה רבה על העזרה שלך.", "להתראות בשבוע הבא."],
    "ar": ["مرحبا، كيف حالك اليوم؟", "كان البحر هادئا طوال فترة الظهيرة.", "سنلتقي غدا قرب المحطة القديمة.", "ركض الكلب نحو الحديقة.", "لم أر مثل هذا الغروب من قبل.", "يغادر القطار في الثامنة تماما.", "شكرا جزيلا على مساعدتك.", "أراك في الأسبوع القادم."],
    "lt": ["Labas rytas, kaip sekasi šiandien?", "Jūra buvo rami visą popietę.", "Susitiksime rytoj prie seno tilto.", "Šuo nubėgo į sodą.", "Niekada nemačiau tokio saulėlydžio.", "Traukinys išvyksta lygiai aštuntą.", "Labai ačiū už jūsų pagalbą.", "Iki pasimatymo kitą savaitę."],
    "vi": ["Xin chào, hôm nay bạn thế nào?", "Biển rất yên tĩnh suốt buổi chiều.", "Chúng ta sẽ gặp nhau ở nhà ga cũ.", "Con chó chạy về phía khu vườn.", "Tôi chưa bao giờ thấy hoàng hôn đẹp như vậy.", "Tàu khởi hành đúng tám giờ.", "Cảm ơn bạn rất nhiều vì đã giúp đỡ.", "Hẹn gặp lại vào tuần sau."],
    "es": ["¿Dónde está el niño que jugaba junto a la ventana?", "Mañana iremos a la montaña con mis padres.", "El señor pidió una paella y un café.", "Nunca había visto un otoño tan bonito.", "La estación está a dos calles de aquí.", "¡Qué alegría verte después de tanto tiempo!", "Por favor, cierra la puerta al salir.", "Gracias, nos vemos el próximo sábado."],
    "hu": ["Árvíztűrő tükörfúrógép.", "Jó reggelt, hogy vagy ma?", "Holnap elmegyünk a hegyekbe.", "A vonat pontosan nyolckor indul.", "Még soha nem láttam ilyen naplementét.", "Köszönöm szépen a segítséget.", "Az öreg híd mellett találkozunk.", "Viszontlátásra a jövő héten."],
    "bg": ["Добър ден, как сте днес?", "Морето беше спокойно цял следобед.", "Ще се видим утре до старата гара.", "Кучето избяга към градината.", "Никога не съм виждал такъв залез.", "Влакът тръгва точно в осем.", "Благодаря ви много за помощта.", "До скоро, ще се видим следващата седмица."],
    "de": ["Zwölf Boxkämpfer jagen Viktor quer über den großen Sylter Deich.", "Morgen fahren wir früh in die Berge.", "Das Brötchen schmeckt heute besonders gut.", "Ich habe noch nie so einen schönen Herbst gesehen.", "Der Zug fährt pünktlich um acht Uhr ab.", "Vielen Dank für Ihre Hilfe!", "Wir treffen uns an der alten Brücke.", "Bis nächste Woche, tschüss!"],
    "zh-hans": ["你好，今天过得怎么样？", "下午的大海一直很平静。", "我们明天在老车站见面。", "小狗跑向了花园。", "我从来没有见过这么美的日落。", "火车八点整出发。", "非常感谢你的帮助。", "下周见。"],
    "zh-hant": ["你好，今天過得怎麼樣？", "下午的大海一直很平靜。", "我們明天在老車站見面。", "小狗跑向了花園。", "我從來沒有見過這麼美的日落。", "火車八點整出發。", "非常感謝你的幫助。", "下週見。"],
    "ja": ["こんにちは、今日はいかがですか。", "午後はずっと海が穏やかでした。", "明日、古い駅の近くで会いましょう。", "犬は庭の方へ走っていきました。", "こんなにきれいな夕日は見たことがありません。", "電車は八時ちょうどに出発します。", "手伝ってくれて本当にありがとう。", "また来週会いましょう。"],
    "ko": ["안녕하세요, 오늘 어떻게 지내세요?", "오후 내내 바다는 잔잔했습니다.", "내일 옛 역 근처에서 만나요.", "강아지가 정원으로 달려갔어요.", "이렇게 아름다운 노을은 처음 봐요.", "기차는 정각 여덟 시에 출발합니다.", "도와주셔서 정말 감사합니다.", "다음 주에 봐요."],
}

# (file name, codec, language). One per encoding US-44.1 names.
CASES = [
    ("utf8", "utf-8", "fr"), ("utf16le", "utf-16-le-bom", "ja"), ("utf16be", "utf-16-be-bom", "ru"),
    ("cp1250", "cp1250", "cs"), ("cp1251", "cp1251", "ru"), ("cp1252", "cp1252", "fr"), ("cp1253", "cp1253", "el"),
    ("cp1254", "cp1254", "tr"), ("cp1255", "cp1255", "he"), ("cp1256", "cp1256", "ar"), ("cp1257", "cp1257", "lt"),
    ("cp1258", "cp1258", "vi"),
    ("iso8859-1", "iso8859-1", "es"), ("iso8859-2", "iso8859-2", "hu"), ("iso8859-5", "iso8859-5", "bg"),
    ("iso8859-7", "iso8859-7", "el"), ("iso8859-9", "iso8859-9", "tr"), ("iso8859-15", "iso8859-15", "de"),
    ("gb18030", "gb18030", "zh-hans"), ("big5", "big5", "zh-hant"), ("shift_jis", "shift_jis", "ja"), ("euc-kr", "euc-kr", "ko"),
]

def srt(lines):
    out = []
    for i, line in enumerate(lines):
        start = 0 if i == 0 else 10 + (i - 1) * 3
        end = 10 if i == 0 else start + 3
        out.append(f"{i + 1}\n00:00:{start:02d},000 --> 00:00:{end:02d},000\n{line}\n")
    return "\n".join(out)

def encode(text, codec):
    if codec == "utf-16-le-bom":
        return b"\xff\xfe" + text.encode("utf-16-le")
    if codec == "utf-16-be-bom":
        return b"\xfe\xff" + text.encode("utf-16-be")
    if codec == "cp1258":
        # Windows-1258 has the base vowels with hat, breve and horn precomposed, and the tone marks combining.
        out = bytearray()
        for ch in text:
            try:
                out += ch.encode("cp1258")
            except UnicodeEncodeError:
                d = unicodedata.normalize("NFD", ch)
                base = unicodedata.normalize("NFC", "".join(c for c in d if unicodedata.combining(c) == 0 or c in "̛̂̆"))
                tones = "".join(c for c in d if unicodedata.combining(c) and c not in "̛̂̆")
                out += (base + tones).encode("cp1258")
        return bytes(out)
    return text.encode(codec)

def main(out):
    os.makedirs(out, exist_ok=True)
    with open(os.path.join(out, "expected.tsv"), "w", encoding="utf-8") as tsv:
        for name, codec, lang in CASES:
            lines = TEXTS[lang]
            open(os.path.join(out, f"{name}.srt"), "wb").write(encode(srt(lines), codec))
            tsv.write(f"{name}\t{unicodedata.normalize('NFC', lines[0])}\n")

main(sys.argv[1])
