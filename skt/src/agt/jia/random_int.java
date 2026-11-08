/*******************************************************************************
 * MIT License
 *
 * Copyright (c) Igor Conrado Alves de Lima <igorcadelima@gmail.com>
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and
 * associated documentation files (the "Software"), to deal in the Software without restriction,
 * including without limitation the rights to use, copy, modify, merge, publish, distribute,
 * sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or
 * substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT
 * NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 * NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM,
 * DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 *******************************************************************************/
package jia;

import java.util.concurrent.ThreadLocalRandom;

import jason.JasonException;
import jason.asSemantics.DefaultInternalAction;
import jason.asSemantics.TransitionSystem;
import jason.asSemantics.Unifier;
import jason.asSyntax.ASSyntax;
import jason.asSyntax.NumberTerm;
import jason.asSyntax.Term;

/**
 * @author igorcadelima
 *
 */
public class random_int extends DefaultInternalAction {

  @Override
  public Object execute(final TransitionSystem ts, final Unifier un, final Term[] args)
      throws Exception {
    try {
      if (!args[0].isNumeric()) {
        throw new JasonException(
            "The first argument of the internal action 'random' is not a number.");
      } else if (!args[1].isNumeric()) {
        throw new JasonException(
            "The second argument of the internal action 'random' is not a number.");
      } else if (!args[2].isVar()) {
        throw new JasonException(
            "The third argument of the internal action 'random' is not a variable.");
      }

      final int min = (int) ((NumberTerm) args[0]).solve();
      final int max = (int) ((NumberTerm) args[1]).solve();
      final int r = ThreadLocalRandom.current()
                                     .nextInt(min, max);

      final NumberTerm randomNumber = ASSyntax.createNumber(r);
      return un.unifies(randomNumber, args[2]);

    } catch (ArrayIndexOutOfBoundsException e) {
      throw new JasonException(
          "The internal action 'random_int' has not received the required arguments.");
    } catch (Exception e) {
      throw new JasonException("Error in internal action 'random_int': " + e, e);
    }
  }
}
