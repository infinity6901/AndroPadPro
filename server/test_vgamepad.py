"""
Phase 0: ViGEmBus Self-Test
Presses the A button programmatically to verify vgamepad + ViGEmBus installation
"""

import time
from vgamepad import VX360Gamepad, XUSB_BUTTON


def test_vgamepad():
    print("Initializing virtual Xbox 360 gamepad...")
    gamepad = VX360Gamepad()

    print("Pressing A button for 2 seconds...")
    gamepad.press_button(button=XUSB_BUTTON.XUSB_GAMEPAD_A)
    gamepad.update()
    time.sleep(2)

    print("Releasing A button...")
    gamepad.release_button(button=XUSB_BUTTON.XUSB_GAMEPAD_A)
    gamepad.update()

    print("Test complete!")

    # Test left stick
    print("Testing left stick...")
    gamepad.left_joystick_float(x_value_float=0.5, y_value_float=0.5)
    gamepad.update()
    time.sleep(1)
    gamepad.left_joystick_float(x_value_float=0.0, y_value_float=0.0)
    gamepad.update()

    # Test triggers
    print("Testing triggers...")
    gamepad.left_trigger_float(value_float=1.0)
    gamepad.update()
    time.sleep(0.5)
    gamepad.left_trigger_float(value_float=0.0)
    gamepad.update()

    print("All tests passed!")


if __name__ == "__main__":
    try:
        test_vgamepad()
    except Exception as e:
        print(f"Error: {e}")
        print(
            "Make sure ViGEmBus driver is installed from: https://github.com/ViGEm/ViGEmBus"
        )
