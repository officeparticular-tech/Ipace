# Pacotes de definições (atualizações sem recompilar)

Um pacote é um ficheiro `.json` que se importa em **Mais → Atualizações → Importar pacote**.
Pode acrescentar ou substituir:

- **modules**: módulos (endereço de pedido em hexadecimal; a resposta é o endereço + 8)
- **params**: parâmetros lidos com o serviço 0x22
  - tipos: `u8`, `s8`, `u16`, `s16`, `u24`, `u32`, `count_nonzero`, `length`
  - o valor final é `bruto × scale + add`, lido a partir do byte `offset`
- **dtcs**: descrições de códigos de erro (pelo código base, por exemplo `"P0AA6"`)
- **procedures**: procedimentos guiados
  - tipos de passo: `info`, `confirm`, `voltage` (min/max), `dcdc` (seconds), `dtc_scan`, `save_report` (label), `wait` (seconds), `clear_dtcs` (target `all_except_hv`), `run` (target = id de outro procedimento), `uds` (tx, req, expect)

## Regras de segurança aplicadas pela app (não se podem contornar com um pacote)

- Serviços UDS permitidos: 0x10 (sessões 01 e 03), 0x14, 0x19, 0x22, 0x31 (subfunções 01 e 03) e 0x3E.
- Um pacote que contenha qualquer outro serviço é **rejeitado por inteiro**. Isto inclui o acesso de segurança (0x27), a escrita de dados (0x2E), o controlo de atuadores (0x2F), o reinício de módulos (0x11) e a programação (0x34–0x37).
- As rotinas (0x31) só correm se o procedimento estiver marcado como `"verified": true` ou se estiver associado ao VIN do carro (`"vins": [...]`).
- Cada rotina e cada pedido para apagar erros pedem confirmação ao utilizador.
- `"available": false` e `"note"` servem para anunciar um procedimento que ainda não existe.

## Exemplo

```json
{
  "format": 1,
  "name": "Exemplo",
  "version": "2026.12.01",
  "min_app": 2,
  "params": [
    {"key": "v12_bcm", "name": "12V vista pelo BCM", "unit": "V", "tx": "726", "did": "DD02",
     "type": "u8", "scale": 0.1, "decimals": 1, "confirmed": false}
  ],
  "dtcs": {"B1A02": "Exemplo de descrição"},
  "procedures": [
    {"id": "verificacao_rapida", "title": "Verificação rápida", "available": true, "verified": true,
     "steps": [
       {"type": "voltage", "label": "12V", "min": 12.2},
       {"type": "dtc_scan"},
       {"type": "save_report", "label": "verificacao_rapida"}
     ]}
  ]
}
```
