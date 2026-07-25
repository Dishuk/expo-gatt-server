import {
  ATT_ERROR_ATTRIBUTE_NOT_FOUND,
  ATT_ERROR_ATTRIBUTE_NOT_LONG,
  ATT_ERROR_INSUFFICIENT_AUTHENTICATION,
  ATT_ERROR_INSUFFICIENT_AUTHORIZATION,
  ATT_ERROR_INSUFFICIENT_ENCRYPTION,
  ATT_ERROR_INSUFFICIENT_ENCRYPTION_KEY_SIZE,
  ATT_ERROR_INSUFFICIENT_RESOURCES,
  ATT_ERROR_INVALID_ATTRIBUTE_VALUE_LENGTH,
  ATT_ERROR_INVALID_HANDLE,
  ATT_ERROR_INVALID_OFFSET,
  ATT_ERROR_INVALID_PDU,
  ATT_ERROR_PREPARE_QUEUE_FULL,
  ATT_ERROR_READ_NOT_PERMITTED,
  ATT_ERROR_REQUEST_NOT_SUPPORTED,
  ATT_ERROR_UNLIKELY_ERROR,
  ATT_ERROR_UNSUPPORTED_GROUP_TYPE,
  ATT_ERROR_WRITE_NOT_PERMITTED,
  ATT_TRANSACTION_TIMEOUT_MS,
  CLIENT_CHARACTERISTIC_CONFIGURATION_UUID,
  DEFAULT_REQUEST_TIMEOUT_MS,
  GATT_SUCCESS,
} from '../index';

/**
 * ATT error codes, in the order and with the decimal values of the Error Code list in Core Spec
 * Vol 3, Part F, Table 3.4 — spelled in decimal so the assertion does not simply repeat the hex
 * literals it is checking.
 */
const SPECIFIED_ERROR_CODES: [number, string][] = [
  [1, 'Invalid Handle'],
  [2, 'Read Not Permitted'],
  [3, 'Write Not Permitted'],
  [4, 'Invalid PDU'],
  [5, 'Insufficient Authentication'],
  [6, 'Request Not Supported'],
  [7, 'Invalid Offset'],
  [8, 'Insufficient Authorization'],
  [9, 'Prepare Queue Full'],
  [10, 'Attribute Not Found'],
  [11, 'Attribute Not Long'],
  [12, 'Insufficient Encryption Key Size'],
  [13, 'Invalid Attribute Value Length'],
  [14, 'Unlikely Error'],
  [15, 'Insufficient Encryption'],
  [16, 'Unsupported Group Type'],
  [17, 'Insufficient Resources'],
];

const EXPORTED_ERROR_CODES_IN_SPEC_ORDER = [
  ATT_ERROR_INVALID_HANDLE,
  ATT_ERROR_READ_NOT_PERMITTED,
  ATT_ERROR_WRITE_NOT_PERMITTED,
  ATT_ERROR_INVALID_PDU,
  ATT_ERROR_INSUFFICIENT_AUTHENTICATION,
  ATT_ERROR_REQUEST_NOT_SUPPORTED,
  ATT_ERROR_INVALID_OFFSET,
  ATT_ERROR_INSUFFICIENT_AUTHORIZATION,
  ATT_ERROR_PREPARE_QUEUE_FULL,
  ATT_ERROR_ATTRIBUTE_NOT_FOUND,
  ATT_ERROR_ATTRIBUTE_NOT_LONG,
  ATT_ERROR_INSUFFICIENT_ENCRYPTION_KEY_SIZE,
  ATT_ERROR_INVALID_ATTRIBUTE_VALUE_LENGTH,
  ATT_ERROR_UNLIKELY_ERROR,
  ATT_ERROR_INSUFFICIENT_ENCRYPTION,
  ATT_ERROR_UNSUPPORTED_GROUP_TYPE,
  ATT_ERROR_INSUFFICIENT_RESOURCES,
];

describe('ATT error constants', () => {
  it.each(SPECIFIED_ERROR_CODES.map((entry, index) => [...entry, index] as const))(
    'assigns %d to %s',
    (code, _name, index) => {
      expect(EXPORTED_ERROR_CODES_IN_SPEC_ORDER[index]).toBe(code);
    },
  );

  it('exports one constant per specified error code', () => {
    expect(EXPORTED_ERROR_CODES_IN_SPEC_ORDER).toHaveLength(SPECIFIED_ERROR_CODES.length);
  });

  it('assigns a distinct code to each constant', () => {
    expect(new Set(EXPORTED_ERROR_CODES_IN_SPEC_ORDER).size).toBe(
      EXPORTED_ERROR_CODES_IN_SPEC_ORDER.length,
    );
  });

  it('keeps every code inside the single byte the ATT error field carries', () => {
    for (const code of EXPORTED_ERROR_CODES_IN_SPEC_ORDER) {
      expect(Number.isInteger(code)).toBe(true);
      expect(code).toBeGreaterThanOrEqual(0);
      expect(code).toBeLessThanOrEqual(255);
    }
  });

  it('reserves zero for success, which is not an error code', () => {
    expect(GATT_SUCCESS).toBe(0);
    expect(EXPORTED_ERROR_CODES_IN_SPEC_ORDER).not.toContain(GATT_SUCCESS);
  });
});

describe('timing constants', () => {
  it('sets the ATT transaction timeout to the 30 seconds the specification requires', () => {
    expect(ATT_TRANSACTION_TIMEOUT_MS).toBe(30000);
  });

  it('leaves the default request timeout below the ATT transaction timeout', () => {
    expect(DEFAULT_REQUEST_TIMEOUT_MS).toBe(10000);
    expect(DEFAULT_REQUEST_TIMEOUT_MS).toBeLessThan(ATT_TRANSACTION_TIMEOUT_MS);
  });
});

describe('CCCD UUID', () => {
  it('is 0x2902 expanded onto the Bluetooth base UUID, lowercase', () => {
    expect(CLIENT_CHARACTERISTIC_CONFIGURATION_UUID).toBe('00002902-0000-1000-8000-00805f9b34fb');
  });
});
