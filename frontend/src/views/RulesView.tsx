import React from 'react';
import { useNavigate } from 'react-router-dom';
import { getCardImagePath } from '../components/TableCanvas';
import './RulesView.css';

/**
 * How to play. Every rule here is the one the engine actually enforces, not
 * generic Yaniv — the two differ in places (a two-card run is legal, a mixed-suit
 * run must empty your hand, Asaf costs your hand *plus* thirty). docs/game-engine.md
 * is the source; shared/rules-contract.json pins the combination cases.
 */

type Spec = [string, string];

const Cards = ({ cards, muted }: { cards: Spec[]; muted?: boolean }) => (
  <div className={`rule-cards${muted ? ' rule-cards-muted' : ''}`}>
    {cards.map(([rank, suit], i) => (
      <img
        key={`${rank}-${suit}-${i}`}
        src={getCardImagePath(rank, suit)}
        alt={`${rank} of ${suit}`}
        className="rule-card"
      />
    ))}
  </div>
);

const Example = ({
  cards,
  legal,
  children,
}: {
  cards: Spec[];
  legal: boolean;
  children: React.ReactNode;
}) => (
  <div className={`rule-example ${legal ? 'is-legal' : 'is-illegal'}`}>
    <Cards cards={cards} muted={!legal} />
    <div className="rule-example-text">
      <span className="rule-verdict">{legal ? '✓ Legal' : '✕ Not allowed'}</span>
      <span>{children}</span>
    </div>
  </div>
);

export default function RulesView() {
  const navigate = useNavigate();

  return (
    <div className="rules-root">
      <header className="rules-topbar">
        <button className="rules-back" onClick={() => navigate('/home')}>
          ← Back
        </button>
        <div className="brand-badge">
          <span className="brand-icon">♠</span>
          <span className="brand-name">YANIV</span>
        </div>
        <span className="rules-topbar-spacer" />
      </header>

      <main className="rules-page">
        <section className="rules-hero">
          <h1>How to Play</h1>
          <p>
            Points are bad. Shed your cards, call <strong>Yaniv</strong> when your hand is low
            enough, and be the last player left standing.
          </p>
        </section>

        <section className="rules-section">
          <h2>The goal</h2>
          <p>
            Everyone starts on zero and picks up points at the end of every round. Reach the
            room's target — <strong>100 by default</strong> — and you are knocked out. The last
            player still in wins the game.
          </p>
        </section>

        <section className="rules-section">
          <h2>The deal</h2>
          <p>
            Each player is dealt <strong>5 cards</strong>, and one card is turned face up to start
            the discard pile. There are no jokers.
          </p>
        </section>

        <section className="rules-section">
          <h2>What your cards are worth</h2>
          <div className="rules-values">
            <div className="value-chip">
              <Cards cards={[['ACE', 'HEARTS']]} />
              <span>1 point</span>
            </div>
            <div className="value-chip">
              <Cards cards={[['SEVEN', 'CLUBS']]} />
              <span>Face value</span>
            </div>
            <div className="value-chip">
              <Cards cards={[['JACK', 'SPADES']]} />
              <span>10 points</span>
            </div>
            <div className="value-chip">
              <Cards cards={[['QUEEN', 'DIAMONDS']]} />
              <span>10 points</span>
            </div>
            <div className="value-chip">
              <Cards cards={[['KING', 'HEARTS']]} />
              <span>10 points</span>
            </div>
          </div>
          <p className="rules-note">
            An Ace is worth 1 when scoring, but counts as either low or high inside a run.
          </p>
        </section>

        <section className="rules-section">
          <h2>Your turn: discard, then draw</h2>
          <p>
            Always in that order. You put cards down first, then take exactly one back — so your
            hand never grows, and usually shrinks.
          </p>

          <h3>1. Discard one of these</h3>

          <Example cards={[['FIVE', 'HEARTS']]} legal>
            <strong>A single card.</strong> Always allowed.
          </Example>

          <Example
            cards={[
              ['EIGHT', 'HEARTS'],
              ['EIGHT', 'SPADES'],
              ['EIGHT', 'CLUBS'],
            ]}
            legal
          >
            <strong>A set</strong> — two, three or four cards of the same rank. Suits do not
            matter.
          </Example>

          <Example
            cards={[
              ['THREE', 'HEARTS'],
              ['FOUR', 'HEARTS'],
              ['FIVE', 'HEARTS'],
            ]}
            legal
          >
            <strong>A run</strong> — two or more cards in sequence, all the <em>same suit</em>.
            Two cards is enough.
          </Example>

          <Example
            cards={[
              ['THREE', 'SPADES'],
              ['FOUR', 'HEARTS'],
              ['FIVE', 'SPADES'],
              ['SIX', 'DIAMONDS'],
              ['SEVEN', 'HEARTS'],
            ]}
            legal
          >
            <strong>A mixed-suit run</strong> — but only if it <em>empties your hand</em>. From a
            full hand that means all five; from a three-card hand, all three.
          </Example>

          <Example
            cards={[
              ['FOUR', 'HEARTS'],
              ['FIVE', 'SPADES'],
              ['SIX', 'DIAMONDS'],
            ]}
            legal={false}
          >
            Three in sequence but different suits, with two cards still left over. Legal only if
            these are your whole hand.
          </Example>

          <Example
            cards={[
              ['KING', 'CLUBS'],
              ['ACE', 'CLUBS'],
              ['TWO', 'CLUBS'],
            ]}
            legal={false}
          >
            A run cannot turn the corner. <strong>A-2-3</strong> and <strong>Q-K-A</strong> are
            fine; <strong>K-A-2</strong> is not.
          </Example>

          <h3>2. Then draw one card</h3>
          <p>Either the top of the deck, or a card from the discard pile — with a catch.</p>
          <p>
            You can only take from the <strong>most recent discard</strong>, and only certain cards
            in it:
          </p>
          <ul className="rules-list">
            <li>
              <strong>A single</strong> — that card.
            </li>
            <li>
              <strong>A set</strong> — any card in it.
            </li>
            <li>
              <strong>A run</strong> — only the two <em>end</em> cards. The middle is locked.
            </li>
          </ul>
          <p className="rules-note">
            What sits on the pile when you draw is the <em>previous</em> player's discard. You can
            never take back what you just threw.
          </p>
        </section>

        <section className="rules-section rules-highlight">
          <h2>Calling Yaniv</h2>
          <p>
            At the start of your turn, if your cards add up to <strong>7 or less</strong>, you can
            call Yaniv instead of playing. That ends the round.
          </p>
          <div className="rules-hand-example">
            <Cards
              cards={[
                ['ACE', 'CLUBS'],
                ['TWO', 'HEARTS'],
                ['FOUR', 'SPADES'],
              ]}
            />
            <span>
              1 + 2 + 4 = <strong>7</strong> — you can call.
            </span>
          </div>
        </section>

        <section className="rules-section rules-danger">
          <h2>Asaf — the risk</h2>
          <p>
            Calling Yaniv does not mean you have won. There is a{' '}
            <strong>15-second window</strong> in which the hands are compared.
          </p>
          <p>
            If <em>any</em> other player's hand is <strong>strictly lower</strong> than yours, it is{' '}
            <strong>Asaf</strong>:
          </p>
          <ul className="rules-list">
            <li>
              You score <strong>your own hand plus 30</strong> — not a flat 30. Get caught holding
              6 and you take 36.
            </li>
            <li>That lowest player scores 0 instead.</li>
          </ul>
          <p>
            A <strong>tie is safe</strong>. If the best opponent matches you exactly, there is no
            Asaf, and you both score 0.
          </p>
          <p className="rules-note">
            Any player can hit the contest button to skip the wait, but it makes no difference to
            the outcome — the lowest hand wins the Asaf whether or not its owner pressed anything.
          </p>
        </section>

        <section className="rules-section">
          <h2>Scoring the round</h2>
          <table className="rules-table">
            <thead>
              <tr>
                <th>Player</th>
                <th>Points added</th>
              </tr>
            </thead>
            <tbody>
              <tr>
                <td>Caller, not caught</td>
                <td className="good">0</td>
              </tr>
              <tr>
                <td>Caller, caught by Asaf</td>
                <td className="bad">their hand + 30</td>
              </tr>
              <tr>
                <td>The player who caught them</td>
                <td className="good">0</td>
              </tr>
              <tr>
                <td>An opponent who tied with the caller</td>
                <td className="good">0</td>
              </tr>
              <tr>
                <td>Everyone else</td>
                <td>their hand value</td>
              </tr>
            </tbody>
          </table>
        </section>

        <section className="rules-section">
          <h2>The halving rule</h2>
          <p>
            If a round lands your total <strong>exactly</strong> on a multiple of 50, it is cut in
            half. 100 becomes 50, 150 becomes 75.
          </p>
          <p>
            It only fires when that round <em>moved</em> you onto the number. Sitting on 50 and
            scoring 0 leaves you on 50.
          </p>
        </section>

        <section className="rules-section">
          <h2>Getting knocked out</h2>
          <p>
            At the end of each round — after any halving — anyone whose total has reached the
            target is eliminated. When one player is left, they win.
          </p>
        </section>

        <section className="rules-section rules-bonus">
          <h2>✨ Matching Rank Bonus</h2>
          <p>
            Discard a <em>single</em> card, draw from the <strong>deck</strong>, and if the card you
            drew is the same rank in a different suit, you can throw it straight down as well.
          </p>
          <div className="rules-hand-example">
            <Cards
              cards={[
                ['NINE', 'CLUBS'],
                ['NINE', 'DIAMONDS'],
              ]}
            />
            <span>Discarded the 9♣, drew the 9♦ — throw it too.</span>
          </div>
          <p>
            You get <strong>no replacement</strong>, so your hand shrinks by an extra card. The one
            exception: if it was your last card, you are dealt a new one — you never end a turn with
            an empty hand.
          </p>
          <p className="rules-note">
            You have 30 seconds to choose, and keeping the card is the default if you do not.
          </p>
        </section>

        <section className="rules-section">
          <h2>When the deck runs out</h2>
          <p>
            The discard pile is shuffled back into the deck, apart from the top discard, which stays
            available to pick up. Play carries on.
          </p>
        </section>

        <section className="rules-section">
          <h2>House rules</h2>
          <p>
            Yaniv has no single rulebook — almost every group plays it a little differently. If you
            have played before, these are the choices this game makes.
          </p>
          <table className="rules-table rules-table-compare">
            <thead>
              <tr>
                <th />
                <th>Often played as</th>
                <th>Here</th>
              </tr>
            </thead>
            <tbody>
              <tr>
                <td>Jokers</td>
                <td>Two, worth 0, wild</td>
                <td className="mine">None — a plain 52-card deck</td>
              </tr>
              <tr>
                <td>Runs</td>
                <td>Three cards minimum</td>
                <td className="mine">Two is enough</td>
              </tr>
              <tr>
                <td>Mixed-suit runs</td>
                <td>Not allowed at all</td>
                <td className="mine">Legal when they empty your hand</td>
              </tr>
              <tr>
                <td>Tying with the caller</td>
                <td>Counts as Asaf — the caller is caught</td>
                <td className="mine">Safe — both score 0</td>
              </tr>
              <tr>
                <td>Who starts the next round</td>
                <td>The player who won it</td>
                <td className="mine">The player seated after the caller</td>
              </tr>
              <tr>
                <td>Matching Rank Bonus</td>
                <td>Not a standard rule</td>
                <td className="mine">Built in</td>
              </tr>
            </tbody>
          </table>
          <p className="rules-note">
            The Yaniv threshold is 7 and the Asaf penalty is your hand plus 30, which is what most
            people play. The knockout target is set per room.
          </p>
        </section>

        <footer className="rules-footer">
          <button className="rules-back-cta" onClick={() => navigate('/home')}>
            Back to the lobby
          </button>
        </footer>
      </main>
    </div>
  );
}
