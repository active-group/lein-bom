# lein-bom

A Leiningen plugin to collect dependencies in the fantasy format called BOM.

## Usage

Check out this repository and, from within the root directory of this project,
run:

    $ lein install

Then, in the project you want to generate a BOM for, run:

    $ lein bom

This will hopefully generate `bom.json` -- this file contains the bom entries
for this project.

## License

Copyright © 2021 Active Group GmbH

This program and the accompanying materials are made available under the
terms of the Eclipse Public License 2.0 which is available at
http://www.eclipse.org/legal/epl-2.0.

This Source Code may also be made available under the following Secondary
Licenses when the conditions for such availability set forth in the Eclipse
Public License, v. 2.0 are satisfied: GNU General Public License as published by
the Free Software Foundation, either version 2 of the License, or (at your
option) any later version, with the GNU Classpath Exception which is available
at https://www.gnu.org/software/classpath/license.html.
