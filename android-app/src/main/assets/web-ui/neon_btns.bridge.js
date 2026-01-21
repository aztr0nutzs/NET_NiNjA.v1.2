
document.addEventListener("click", (e) => {
  const a = e.target.closest("a.neon-btn");
  if (!a) return;
  if (a.getAttribute("href") === "#") e.preventDefault();
});
