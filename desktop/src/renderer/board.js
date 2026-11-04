const $ = (id) => document.getElementById(id); let frames = [], i = 0;
$('close').innerHTML = ICON.close;
function show(n) { i = Math.max(0, Math.min(frames.length - 1, n)); $('stage').innerHTML = frames[i].svg; $('cap').textContent = frames[i].caption || ''; $('count').textContent = (i + 1) + ' / ' + frames.length; $('prev').disabled = i === 0; $('next').disabled = i === frames.length - 1; $('dots').innerHTML = frames.map((_, k) => `<i class="${k === i ? 'on' : ''}"></i>`).join(''); }
window.api.on('board', (d) => { frames = d.frames || []; $('title').textContent = d.title || ''; document.querySelector('footer').style.display = frames.length > 1 ? 'flex' : 'none'; $('dots').style.display = frames.length > 1 ? 'flex' : 'none'; if (frames.length) show(0); });
$('prev').onclick = () => show(i - 1); $('next').onclick = () => show(i + 1); $('close').onclick = () => window.api.send('board:close');
document.addEventListener('keydown', (e) => { if (e.key === 'Escape') window.api.send('board:close'); else if (e.key === 'ArrowRight' || e.key === ' ') show(i + 1); else if (e.key === 'ArrowLeft') show(i - 1); });
