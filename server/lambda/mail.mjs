// Envio de e-mail (codigo de confirmacao / troca de senha) pelo Amazon SES.
// Se o SES ainda nao estiver liberado, devolve false e o codigo fica visivel no painel (aba Codigos).
let client = null;

export async function sendCode(to, code, purpose) {
  const from = process.env.MAIL_FROM;
  if (!from || process.env.STORE === "memory") return false;
  const subject = purpose === "reset" ? `Código para trocar a senha: ${code}` : `Seu código de confirmação: ${code}`;
  const what = purpose === "reset" ? "trocar a senha da sua conta" : "confirmar seu e-mail";
  const text = `Olá!\n\nUse o código ${code} para ${what} no Filtro Inteligente.\nEle vale por 15 minutos.\n\nSe não foi você, ignore este e-mail.`;
  const html = `<div style="font-family:Arial,sans-serif;font-size:15px;color:#0f172a">
<p>Olá!</p><p>Use o código abaixo para ${what} no <b>Filtro Inteligente</b>:</p>
<p style="font-size:30px;font-weight:700;letter-spacing:6px">${code}</p>
<p style="color:#475569">Ele vale por 15 minutos. Se não foi você, ignore este e-mail.</p></div>`;
  try {
    if (!client) {
      const { SESv2Client } = await import("@aws-sdk/client-sesv2");
      client = new SESv2Client({ region: process.env.MAIL_REGION || "sa-east-1" });
    }
    const { SendEmailCommand } = await import("@aws-sdk/client-sesv2");
    await client.send(new SendEmailCommand({
      FromEmailAddress: from,
      Destination: { ToAddresses: [to] },
      Content: { Simple: { Subject: { Data: subject, Charset: "UTF-8" }, Body: { Text: { Data: text, Charset: "UTF-8" }, Html: { Data: html, Charset: "UTF-8" } } } }
    }));
    return true;
  } catch (e) {
    console.error("email falhou", to, e?.name, e?.message);
    return false;
  }
}
