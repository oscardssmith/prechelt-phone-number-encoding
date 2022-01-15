import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.lang.System.Logger.Level.INFO;
import static java.nio.charset.StandardCharsets.US_ASCII;

/**
 * My first solution to the Prechelt phone-encoding problem.
 *
 * @author Renato Athaydes
 */
final class Main {
    public static void main( String[] args ) throws IOException {
        var words = new InputParser( WordsInputCleaner::clean )
                .parse( new File( args.length > 0 ? args[ 0 ] : "tests/words.txt" ) );

        var encoder = new PhoneNumberEncoder( words );
        var logger = System.getLogger( "Main" );

        new InputParser( PhoneNumberCleaner::clean )
                .parse( new File( args.length > 1 ? args[ 1 ] : "tests/numbers.txt" ) )
                .forEach( phone ->
                        encoder.encode( phone, item -> {
                            logger.log( INFO, "{0}: {1}", item.original(), item.result() );
                        } ) );

    }

}

/**
 * The following mapping from letters to digits is given:
 * <p>
 * E | J N Q | R W X | D S Y | F T | A M | C I V | B K U | L O P | G H Z
 * e | j n q | r w x | d s y | f t | a m | c i v | b k u | l o p | g h z
 * 0 |   1   |   2   |   3   |  4  |  5  |   6   |   7   |   8   |   9
 * <p>
 * For the encoding only the letters are used, but
 * the words must be printed in exactly the form given in the dictionary.
 * Leading non-letters do not occur in the dictionary.
 * <p>
 * Encodings of phone numbers can consist of a single word or of multiple
 * words separated by spaces. The encodings are built word by word from
 * left to right. If and only if at a particular point no word at all from
 * the dictionary can be inserted, a single digit from the phone number can
 * be copied to the encoding instead. Two subsequent digits are never
 * allowed, though. To put it differently: In a partial encoding that
 * currently covers k digits, digit k+1 is encoded by itself if and only if,
 * first, digit k was not encoded by a digit and, second, there is no word
 * in the dictionary that can be used in the encoding starting at digit k+1.
 */
final class PhoneNumberEncoder {
    private final Trie dictionary;

    PhoneNumberEncoder( Stream<Item> words ) {
        dictionary = new Trie();
        //long start = System.currentTimeMillis();
        words.forEach( dictionary::put );
        //System.out.println( "Took " + ( System.currentTimeMillis() - start ) + "ms to load dictionary" );
    }

    void encode( Item phone, Consumer<Item> onSolution ) {
        dictionary.forEachSolution( phone, onSolution );
    }
}

final class Trie {
    // A node is an item in a List representing a current candidate for a solution...
    // A full solution is a List of Node's where each Node is a word or a char digit
    static class Node {
        private final Item item;
        private final int digit;

        Node( Item item ) {
            assert item != null;
            this.item = item;
            this.digit = -1;
        }

        Node( int digit ) {
            this.item = null;
            this.digit = digit;
        }

        static Item toItem( List<Node> nodes, Item phone ) {
            var solution = nodes.stream()
                    .map( node -> node.item == null ? node.digit : node.item.original() )
                    .map( Object::toString )
                    .collect( Collectors.joining( " " ) );
            return new Item( phone.original(), solution );
        }
    }

    final Trie[] items = new Trie[ 10 ];
    final List<Item> values = new ArrayList<>( 2 );
    final Trie root;

    Trie() {
        root = this;
    }

    Trie( Trie root ) {
        this.root = root;
    }

    void put( Item item ) {
        put( item.result().toCharArray(), 0, item );
    }

    void put( char[] chars, int index, Item item ) {
        if ( index < chars.length ) {
            var digit = charToDigit( chars[ index ] );
            Trie current = items[ digit ];
            if ( current == null ) {
                current = new Trie( root );
                items[ digit ] = current;
            }
            current.put( chars, index + 1, item );
        } else {
            values.add( item );
        }
    }

    void forEachSolution( Item phone, Consumer<Item> onSolution ) {
        char[] chars = phone.result().toCharArray();
        completeSolution( List.of(), chars, 0, true, solution ->
                onSolution.accept( Node.toItem( solution, phone ) ) );
    }

    /**
     * @param solution         current partial solution
     * @param chars            phone number digits
     * @param index            current index on chars
     * @param allowInsertDigit whether the current position may accept a digit in the solution
     * @param onSolution       callback to call when a full solution is found
     * @return true if at least one word was found recursing into this branch
     */
    boolean completeSolution( List<Node> solution,
                              char[] chars,
                              int index,
                              boolean allowInsertDigit,
                              Consumer<List<Node>> onSolution ) {
        var wordFound = false;
        if ( index < chars.length ) {
            var digit = chars[ index ] - 48;
            var trie = items[ digit ];
            if ( trie != null ) {
                // keep going, there may be longer words which also match
                wordFound = trie.completeSolution( solution, chars, index + 1, false, onSolution );
                var atEndOfInput = index + 1 == chars.length;

                // each word in this trie may provide a new solution
                for ( Item word : trie.values ) {
                    wordFound = true;
                    var nextSolution = append( solution, new Node( word ) );
                    // accept solution if we're at the end
                    if ( atEndOfInput ) {
                        onSolution.accept( nextSolution );
                    } else {
                        root.completeSolution( nextSolution, chars, index + 1, true, onSolution );
                    }
                }
            }

            // If and only if at a particular point no word at all from
            // the dictionary can be inserted, a single digit from the phone number can
            // be copied to the encoding instead.
            if ( !wordFound && this == root ) {
                if ( allowInsertDigit ) {
                    tryInjectDigit( solution, chars, index, onSolution );
                }
            }
        }
        return wordFound;
    }

    private void tryInjectDigit( List<Node> solution, char[] chars,
                                 int index, Consumer<List<Node>> onSolution ) {
        solution = append( solution, new Node( chars[ index ] - 48 ) );
        // accept solution if we're at the end
        if ( index + 1 == chars.length ) {
            onSolution.accept( solution );
            return;
        }
        root.completeSolution( solution, chars, index + 1, false, onSolution );
    }

    //    E | J N Q | R W X | D S Y | F T | A M | C I V | B K U | L O P | G H Z
    //    e | j n q | r w x | d s y | f t | a m | c i v | b k u | l o p | g h z
    //    0 |   1   |   2   |   3   |  4  |  5  |   6   |   7   |   8   |   9
    static int charToDigit( char c ) {
        return switch ( c ) {
            case 'e' -> 0;
            case 'j', 'n', 'q' -> 1;
            case 'r', 'w', 'x' -> 2;
            case 'd', 's', 'y' -> 3;
            case 'f', 't' -> 4;
            case 'a', 'm' -> 5;
            case 'c', 'i', 'v' -> 6;
            case 'b', 'k', 'u' -> 7;
            case 'l', 'o', 'p' -> 8;
            case 'g', 'h', 'z' -> 9;
            default -> throw new RuntimeException( "Invalid char: " + c );
        };
    }

    static <T> List<T> append( List<T> list, T item ) {
        var result = new ArrayList<T>( list.size() + 1 );
        result.addAll( list );
        result.add( item );
        return Collections.unmodifiableList( result );
    }
}

record Item(String original, String result) {

    Item( String result ) {
        this( result, result );
    }

    @Override
    public String toString() {
        return original + ": " + result;
    }
}

final class InputParser {
    private final Function<String, String> cleaner;

    public InputParser( Function<String, String> cleaner ) {
        this.cleaner = cleaner;
    }

    Stream<Item> parse( File file ) throws IOException {
        return parse( new BufferedReader( new FileReader( file, US_ASCII ) ) );
    }

    Stream<Item> parse( BufferedReader reader ) {
        return reader.lines()
                .map( line -> new Item( line, cleaner.apply( line ) ) )
                .filter( word -> !word.result().isEmpty() );
    }

}

/**
 * The words are taken from a dictionary which
 * is given as an alphabetically sorted ASCII file (one word per line).
 * <p>
 * [NOTE: The dictionary is in German and contains umlaut characters
 * encoded as double-quotes.  The double-quotes should be ignored.  EG.]
 */
final class WordsInputCleaner {
    private static final Pattern NOT_LETTERS = Pattern.compile( "[^a-zA-Z]" );

    static String clean( String word ) {
        return NOT_LETTERS.matcher( word ).replaceAll( "" ).toLowerCase( Locale.ENGLISH );
    }
}

/**
 * A phone number is an
 * arbitrary(!) string of dashes - , slashes / and digits. The dashes and
 * slashes will not be encoded.
 */
final class PhoneNumberCleaner {
    private static final Pattern IGNORE_CHARS = Pattern.compile( "[-/]" );

    static String clean( String phone ) {
        return IGNORE_CHARS.matcher( phone ).replaceAll( "" );
    }
}
