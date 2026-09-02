/**
 * Runs the shared rule cases in shared/rules-contract.json against the CLIENT
 * implementation. The server runs the same file in RulesContractTest.java.
 *
 * The discard rules exist twice — once in Java for authority, once here so the UI can
 * grey out illegal selections without a round trip. Nothing stops the two drifting
 * apart, so both are pinned to one case table: a rule change that updates only one
 * side fails here or there.
 */
import * as fs from 'fs';
import * as path from 'path';
import { Card, calculateHandScore, getRankValueHigh, getRankValueLow, isValidCombination } from './yanivRules';

type ContractCard = [string, string, string]; // [id, rank, suit]

interface CombinationCase {
  name: string;
  handSize: number;
  cards: ContractCard[];
  valid: boolean;
}

interface HandScoreCase {
  name: string;
  cards: ContractCard[];
  score: number;
}

interface SequenceValueCase {
  rank: string;
  low: number;
  high: number;
}

interface Contract {
  combinations: CombinationCase[];
  handScores: HandScoreCase[];
  sequenceValues: SequenceValueCase[];
}

// frontend/src/utils -> repo root
const CONTRACT_PATH = path.resolve(__dirname, '../../../shared/rules-contract.json');

const loadContract = (): Contract => {
  if (!fs.existsSync(CONTRACT_PATH)) {
    throw new Error(
      `Could not find ${CONTRACT_PATH}. This file is shared with the backend test ` +
        `(RulesContractTest.java) and must exist.`
    );
  }
  return JSON.parse(fs.readFileSync(CONTRACT_PATH, 'utf8')) as Contract;
};

const toCards = (cards: ContractCard[]): Card[] =>
  cards.map(([id, rank, suit]) => ({ id, rank, suit }));

const contract = loadContract();

describe('discard rules match the shared contract', () => {
  it('the contract defines cases', () => {
    expect(contract.combinations.length).toBeGreaterThan(0);
  });

  contract.combinations.forEach((testCase) => {
    it(testCase.name, () => {
      const result = isValidCombination(toCards(testCase.cards), testCase.handSize);
      expect(result.valid).toBe(testCase.valid);
    });
  });
});

describe('hand scoring matches the shared contract', () => {
  it('the contract defines cases', () => {
    expect(contract.handScores.length).toBeGreaterThan(0);
  });

  contract.handScores.forEach((testCase) => {
    it(testCase.name, () => {
      expect(calculateHandScore(toCards(testCase.cards))).toBe(testCase.score);
    });
  });
});

describe('the sequence ladder matches the shared contract', () => {
  it('the contract defines cases', () => {
    expect(contract.sequenceValues.length).toBeGreaterThan(0);
  });

  contract.sequenceValues.forEach((testCase) => {
    it(`${testCase.rank} sequence position`, () => {
      expect(getRankValueLow(testCase.rank)).toBe(testCase.low);
      expect(getRankValueHigh(testCase.rank)).toBe(testCase.high);
    });
  });
});
