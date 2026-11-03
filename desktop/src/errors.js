'use strict';
// One short, actionable sentence for API / network failures (spoken and shown).
function explain(raw, dutch) {
  const m = String(raw).toLowerCase(); const t = (nl, en) => (dutch ? nl : en);
  if (/network:|fetch failed|enotfound|econn|etimedout|timeout|timed out|abort/.test(m)) return t('Ik heb geen verbinding met internet.', "I can't reach the internet.");
  if (/api 401|authentication|invalid x-api-key/.test(m)) return t('Je API-sleutel werkt niet. Controleer hem in de instellingen.', "Your API key doesn't work. Check it in settings.");
  if (/credit balance|api 402|billing/.test(m)) return t('Je tegoed bij Anthropic is op. Vul het aan en probeer opnieuw.', 'Your Anthropic credit has run out. Top it up and try again.');
  if (/api 403|permission/.test(m)) return t('Je sleutel heeft geen toegang tot dit model. Kies een ander model in de instellingen.', "Your key can't use this model. Pick another model in settings.");
  if (/api 404|not_found/.test(m)) return t('Dat model bestaat niet. Kies een ander model in de instellingen.', "That model doesn't exist. Pick another model in settings.");
  if (/api 429|rate_limit/.test(m)) return t('Te veel verzoeken tegelijk. Probeer het over een minuutje opnieuw.', 'Too many requests. Try again in a minute.');
  if (/api 5|overloaded/.test(m)) return t('Claude is even overbelast. Probeer het zo opnieuw.', 'Claude is overloaded for a moment. Try again shortly.');
  if (/no api key|hearth url/.test(m)) return t('Voeg eerst je API-sleutel toe in de instellingen.', 'Add your API key in settings first.');
  return t('Er ging iets mis: ', 'Something went wrong: ') + String(raw).slice(0, 120);
}
module.exports = { explain };
