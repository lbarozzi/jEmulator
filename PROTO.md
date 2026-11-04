# Protocollo seriale simulato

Il simulatore espone un semplice protocollo testuale, una richiesta per riga. Le
richieste arrivano da una singola FIFO POSIX. Le righe terminano con `\n`. Poiche'
una FIFO POSIX e' unidirezionale, le risposte non vengono scritte sulla stessa
FIFO: sono visibili nel log della finestra JavaFX.

## FIFO

Su Linux, all'avvio il programma crea la FIFO se non esiste:

- Percorso predefinito: `/tmp/jemulator`

Il percorso puo' essere cambiato nella finestra prima di premere **Avvia**. La
FIFO esistente viene riutilizzata, ma un file normale con lo stesso nome non
viene sovrascritto.

Il simulatore apre la FIFO in lettura/scrittura per non attendere un client
all'avvio. Il client scrive le richieste nella FIFO; ogni coppia richiesta e
risposta e' mostrata nel riquadro **Traffico protocollo** della UI.

Esempio da shell:

```sh
printf 'get in0\nget an0\nset out1 1\nset out2 0\n' > /tmp/jemulator
```

L'output del comando `printf` e' soltanto il contenuto inviato. Per leggere le
risposte in modo interattivo serve un endpoint duplex, ad esempio una pseudo
seriale (PTY), non una singola FIFO POSIX.

## Comandi

I nomi dei comandi e dei canali sono in minuscolo; gli indici partono da zero.
Gli spazi ripetuti sono ammessi.

| Comando | Descrizione | Risposta nel log UI |
|---|---|---|
| `get inN` | Legge l'ingresso digitale `in0`–`in3` | `0` oppure `1` |
| `get anN` | Legge l'ingresso analogico `an0`–`an3` | Intero da `0` a `4095` |
| `get outN` | Legge l'uscita digitale `out0`–`out3` | `0` oppure `1` |
| `set outN V` | Imposta l'uscita digitale, `V` e' `0` o `1` | Valore impostato |
| `get pwmN` | Legge il PWM `pwm0`–`pwm1` | Intero da `0` a `100` |
| `set pwmN V` | Imposta il PWM, `V` e' da `0` a `100` | Valore impostato |

`N` e' un singolo indice valido per il tipo di canale. I potenziometri, gli
switch, i LED e i cursori PWM nella UI aggiornano rispettivamente `anN`,
`inN`, `outN` e `pwmN`.

## Esempi

Richiesta:

```text
get in0
get an0
set out1 1
set out2 0
```

Possibili righe nel log, con `an0` al valore 2046:

```text
0
2046
1
0
```

Un comando non valido produce una risposta nel log che inizia con `ERR `, per esempio
`ERR unknown channel`, `ERR invalid value` o `ERR unknown command`. Anche gli
errori occupano una sola riga e non interrompono l'ascolto.
