# KEY-MONEY DATA SOLUTIONS website

Clients open the site, pick one of your offers, choose **For myself** or **For a friend**, and get an M-Pesa prompt. When they pay, the Bn app dials the bundle to the right number.

## How it fits together
1. The Bn app publishes your **active offers** (name and price only, never the USSD codes) to this server.
2. The website lists them. A client taps one and presses Pay.
3. The server sends the STK push (M-Pesa prompt) to the client's number.
4. Safaricom confirms. The server marks the order paid.
5. The app keeps one request open to the server, so the moment payment is confirmed it learns about it (well under a second) and dials the bundle to the recipient number.

The M-Pesa SMS your till also receives for that payment is ignored by the app, so nobody gets the bundle twice.

## Run it (needs Node 18 or newer, a public HTTPS address)
```
cd website
SHOP_KEY=pick-a-long-secret \
DARAJA_ENV=production \
DARAJA_KEY=... DARAJA_SECRET=... \
SHORTCODE=<your head office / store number> TILL=3563482 PASSKEY=... \
TXN_TYPE=CustomerBuyGoodsOnline \
PUBLIC_URL=https://your-domain.com \
node server.js
```
| Setting | What it is |
|---|---|
| `SHOP_KEY` | Secret you type into the app. Keep it private. |
| `DARAJA_KEY`, `DARAJA_SECRET` | From your app on developer.safaricom.co.ke |
| `SHORTCODE` | Business shortcode used for the STK password (for a Buy Goods till this is the head office number) |
| `TILL` | The till that receives the money. Already set to **3563482** by default |
| `PASSKEY` | Lipa na M-Pesa Online passkey from Safaricom |
| `TXN_TYPE` | Already `CustomerBuyGoodsOnline` (Buy Goods and Services). Leave it |
| `PUBLIC_URL` | Your site's public https address. Safaricom sends payment results to `PUBLIC_URL/api/mpesa/callback` |
| `DATA_DIR` | Folder where orders are saved. Point it at a persistent disk (for example /data) |
| `CALLBACK_TOKEN` | Optional second secret for the payment callback (defaults to SHOP_KEY) |
| `DARAJA_ENV` | `production`, or leave out for the sandbox |

Use any host that runs Node and keeps files (a small VPS, Render with a disk, Railway). Orders and offers are stored in `data.json` next to `server.js`, so keep that file between restarts.

## Connect the app
Bn app > Settings > General > **Website**: tick **Sell from my website**, enter your website address and the shop key, then press **Test connection**.

## Your till (Buy Goods and Services, 3563482)
For a till, Safaricom needs two numbers: the **till number** (3563482, where the money goes) and the **head office / store number** (used to sign the request, set as `SHORTCODE`). You get the head office number and the passkey when you go live on developer.safaricom.co.ke. Ask Safaricom if you cannot find them.

## Before going live
- Test with the Safaricom sandbox first (`DARAJA_ENV` unset, sandbox shortcode 174379 and its test passkey).
- Make sure the phone running Bn is online and has auto-dial on, or web orders will wait in the queue.
- For instant selling, keep **auto-dial** on and the app open and online. With auto-dial off, web orders wait in the queue until you tap Dial now.
- If you change a price in the app, the website updates within about 15 seconds.
