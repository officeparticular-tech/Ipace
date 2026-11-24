# I-Pace Hugo Valverde PT

**Multi-carro:** ao abrir, toque no nome do carro no cabeçalho para escolher entre o **Jaguar I-Pace (2018)** e o **Nissan Leaf (2015, 24 kWh)**. O I-Pace tem diagnóstico completo; o Leaf traz o núcleo da bateria (SOH, Hx, AHr, células, temperaturas), com valores a confirmar no carro.

**Tipos de ligação:** Bluetooth clássico (MX+, LX, vLinker), Bluetooth LE (CX, Veepeak) e **WiFi** (adaptador OBD WiFi; ligue primeiro o telemóvel à rede WiFi do adaptador e use 192.168.0.10:35000).


App Android de diagnóstico para o Jaguar I-Pace (2018, ano-modelo 2019) através de um adaptador Bluetooth **OBDLink MX+**.

## Instalar no Samsung S22 Ultra (primeira vez, ~20 min)

1. No portátil, instale o **Android Studio** (gratuito): https://developer.android.com/studio
2. Descompacte esta pasta. No Android Studio: **File → Open** e escolha a pasta `ipace-diag`.
   Na primeira abertura são descarregados automaticamente o Gradle e as ferramentas Android (alguns minutos).
3. No telemóvel, ative o modo de programador:
   **Definições → Acerca do telefone → Informações de software → tocar 7 vezes em "Número de compilação"**.
   Depois, em **Definições → Opções de programador**, ative **Depuração USB**.
4. Ligue o telemóvel ao portátil por cabo USB e aceite o pedido no telemóvel.
5. No Android Studio, escolha o S22 na lista de dispositivos e carregue em **▶ Run**. A app fica instalada.

Alternativa sem cabo: **Build → Build App Bundle(s) / APK(s) → Build APK(s)**. O ficheiro fica em
`app/build/outputs/apk/debug/app-debug.apk`. Envie-o para o telemóvel e instale-o, autorizando "fontes desconhecidas".

## Primeira utilização

- Aceite os termos de utilização (obrigatório).
- Para explorar sem o carro: **Início → Explorar em modo demonstração**. Todos os dados são simulados.
- Para usar no carro, há dois tipos de adaptador:
  - **Bluetooth clássico** (OBDLink MX+, LX, vLinker MC+): emparelhe primeiro nas definições de Bluetooth do Android, depois **Início → Ligar (Bluetooth clássico)**.
  - **Bluetooth LE** (OBDLink CX, Veepeak BLE): não precisa de emparelhar. **Início → Ligar (Bluetooth LE)** e a app procura-o (concede a permissão de dispositivos próximos).
- No fim, retire o adaptador da tomada.

## O que faz

| Separador | Função |
|---|---|
| Início | Mostradores de carga e de 12V, resumo |
| Erros | Lê os erros de cerca de 40 módulos. Pode apagá-los, com um relatório gravado automaticamente antes e um aviso duplo se houver erros de alta tensão |
| Bateria | Bateria de alta tensão: carga, saúde (capacidade/potência), células, temperaturas, corrente, módulos sinalizados (pós-recall H441) |
| 12V | 4 testes (repouso, queda ao acordar, DC/DC, noite). Avaliação da bateria auxiliar por multímetro e procura da tensão nos módulos |
| Registo | Grava o carregamento rápido segundo a segundo e marca o instante em que aparece cada erro |
| Mais | Relatório PDF completo, **atualizações por pacote**, **procedimentos guiados**, **modo escuta**, descoberta de parâmetros, tráfego bruto, termos |

### Troca das baterias de 12V (separador 12V → "Substituir baterias 12V")

1. **Antes da troca:** grava o estado do carro (tensão, erros, relatório).
2. Troque as baterias, seguindo os cuidados indicados no ecrã.
3. **Depois da troca:** confirma que o conversor DC/DC carrega bem e lê os erros. Depois faz o **registo da bateria principal (BMS)**, se estiver disponível, e apaga os erros causados pela troca. Os erros de alta tensão são mantidos.

O identificador da rotina de registo do I-Pace **não é público**. Há duas formas de o obter:

- **Modo escuta** (Mais): com um cabo OBD em Y, a app grava em silêncio o que a máquina de uma oficina envia ao fazer o registo no seu carro. Depois guarda essa rotina associada ao seu VIN.
- **Pacote de definições** atualizado, quando a rotina for conhecida.

### Atualizações

- **Novas funções em forma de dados** (parâmetros, descrições de erros, procedimentos): importe um pacote `.json`. O formato está em `docs/FORMATO-PACOTES.md`.
- **Novas versões da app:** abra a nova versão do projeto no Android Studio e carregue em Run. A app instala-se por cima e mantém os dados, desde que seja compilada no mesmo portátil (mesma assinatura).

Os ficheiros ficam em **Transferências/IPaceDiag** (app "Os meus ficheiros").

## O que não faz (de propósito)

- Não escreve na configuração do carro e não usa acesso de segurança. Por isso não ativa funções nem lê a saúde de cada um dos 36 módulos da bateria.
- Só corre rotinas em procedimentos verificados ou capturados no próprio carro, e pede sempre confirmação.
- Não alcança os módulos que comunicam por Ethernet (DoIP).

## Fontes

- Endereços dos módulos e fórmulas do BECM: Open Vehicle Monitoring System, componente Jaguar I-Pace (licença MIT, © 2021 Didier Ernotte).
- Critérios de 12V: boletins JLR SSM74274 e SSM74458.
- Parâmetros marcados "por confirmar" vêm da comunidade de proprietários.

## Aviso

Ferramenta experimental, não oficial e sem garantias. Pode conter erros de programação não detetados.
O utilizador assume integralmente todos os riscos e responsabilidades (ver os termos completos na app).
